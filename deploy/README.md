# Deploying on an Oracle Cloud Always Free VM

Target: Ubuntu 24.04 (ARM), one VM, the app behind Caddy with a free Let's Encrypt certificate.

## 1. One-time setup

```bash
sudo apt update && sudo apt install -y openjdk-21-jre-headless postgresql caddy
sudo -u postgres createuser --pwprompt sitare          # choose a strong password
sudo -u postgres createdb -O sitare sitare_quiz

sudo useradd --system --home /opt/sitare-quiz --shell /usr/sbin/nologin sitare
sudo mkdir -p /opt/sitare-quiz /var/backups/sitare-quiz
sudo chown sitare: /opt/sitare-quiz /var/backups/sitare-quiz
```

Open only ports 22, 80 and 443 in the Oracle security list. Point a DNS name (for example `quiz.sitare.org`) at the
VM's public IP.

## 2. Configuration

Create `/etc/sitare-quiz.env` (mode 600, owned by root):

```
DB_URL=jdbc:postgresql://localhost:5432/sitare_quiz
DB_USER=sitare
DB_PASSWORD=...
APP_FACULTY_EMAIL=saurabh@sitare.org
APP_FACULTY_NAME=Saurabh Pandey
APP_FACULTY_PASSWORD=...        # only used to create the first account
```

## 3. Install the service and Caddy

```bash
sudo cp sitare-quiz.service /etc/systemd/system/
sudo cp Caddyfile /etc/caddy/Caddyfile        # edit the domain name first
sudo cp target/sitare-quiz-*.jar /opt/sitare-quiz/sitare-quiz.jar
sudo systemctl daemon-reload
sudo systemctl enable --now sitare-quiz
sudo systemctl reload caddy
```

## 4. Backups

```bash
sudo cp backup.sh /opt/sitare-quiz/backup.sh && sudo chmod +x /opt/sitare-quiz/backup.sh
echo '30 2 * * * root /opt/sitare-quiz/backup.sh' | sudo tee /etc/cron.d/sitare-quiz-backup
```

Run `backup.sh` by hand right after each quiz closes as well. Copy the dumps to Oracle Object Storage
(`oci os object put ...`) so they survive the loss of the VM.

## Quiz-day checklist (30 minutes before start)

- `curl -s https://quiz.sitare.org/actuator/health` returns `{"status":"UP"}`
- `df -h /` shows more than 2 GB free
- the newest file in `/var/backups/sitare-quiz` is less than 24 hours old
- do not deploy while a quiz is published and running

## Fallback

If the VM is down, run the same jar on a lab PC with PostgreSQL, restore the latest dump
(`pg_restore -d sitare_quiz latest.dump`) and give students the lab PC's local address.
