# GeoReckon Cloud & AWS EC2 Deployment Guide

This guide describes how to deploy the GeoReckon Pure-Python Server and Socket.IO real-time navigation backend from GitHub to an AWS EC2 instance (Ubuntu 22.04 / 24.04 LTS or Amazon Linux 2023).

---

## 1. Deployment Architecture Overview

```mermaid
graph LR
    subgraph Client
        Android[Android InertiaX App]
    end
    subgraph Cloud [AWS EC2 Instance]
        Nginx[Nginx Reverse Proxy / SSL (Port 443 WSS)]
        Server[GeoReckon Flask-SocketIO (Port 5000)]
        Weights[KinoNet Weights (kinonet_r2_best.pth)]
    end
    Android -- WSS WebSocket (10 Hz) --> Nginx
    Nginx --> Server
    Server --> Weights
```

---

## 2. Prerequisites on AWS EC2

1. **Instance Recommendation:**
   * **Instance Type:** `t3.medium` or `t3.large` (2 vCPU, 4 GiB RAM minimum recommended for PyTorch inference and concurrent sessions).
   * **OS:** Ubuntu 22.04 LTS or Ubuntu 24.04 LTS.
   * **Security Group Inbound Rules:**
     * `TCP 22` (SSH - restrict to admin IP)
     * `TCP 80` (HTTP - for Let's Encrypt verification)
     * `TCP 443` (HTTPS / WSS - open to client traffic)
     * *(Optional during dev)* `TCP 5000` (Direct Socket.IO port)

---

## 3. Step-by-Step Deployment Procedure

### Step 1: Clone the GitHub Repository
```bash
cd /opt
sudo git clone https://github.com/animeshtripathii/GeoReckon.git georeckon
sudo chown -R $USER:$USER /opt/georeckon
cd /opt/georeckon
```

### Step 2: Create Python Virtual Environment
Ensure Python 3.10+ is installed:
```bash
sudo apt-get update && sudo apt-get install -y python3-venv python3-pip
python3 -m venv .venv
source .venv/bin/activate
```

### Step 3: Install Dependencies
Install PyTorch CPU wheel followed by the server dependencies:
```bash
pip install --upgrade pip setuptools wheel
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements-server.txt
```

### Step 4: Transfer KinoNet Model Weights
Model weights (`kinonet_r2_best.pth`) are tracked separately from source code and should be transferred securely via SCP or AWS S3:

**Via SCP from Local Machine:**
```bash
scp -i /path/to/your-key.pem ./InertiaX/kinonet_r2_best.pth ubuntu@<ec2-public-ip>:/opt/georeckon/models/kinonet_r2_best.pth
```

**Or from AWS S3:**
```bash
mkdir -p /opt/georeckon/models
aws s3 cp s3://your-secure-bucket/kinonet_r2_best.pth /opt/georeckon/models/kinonet_r2_best.pth
```

### Step 5: Configure Environment Variables
Copy `.env.example` to `.env` and set your configuration:
```bash
cp .env.example .env
```
Edit `.env`:
```env
HOST=0.0.0.0
PORT=5000
SECRET_KEY=your_generated_secure_secret_key_here
KINONET_WEIGHTS=/opt/georeckon/models/kinonet_r2_best.pth
ENVIRONMENT=production
CORS_ALLOWED_ORIGINS=*
```

### Step 6: Run Tests & Verification
Run the regression suite to verify engine correctness on the EC2 host:
```bash
python -m unittest discover -s georeckon/tests -p "test_*.py"
```
Ensure all tests report `OK`.

### Step 7: Systemd Service Setup (Production Daemon)
Create a systemd unit file for resilient auto-restart:
```bash
sudo nano /etc/systemd/system/georeckon.service
```
Add the following configuration:
```ini
[Unit]
Description=GeoReckon Real-Time Socket.IO Server
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/georeckon
EnvironmentFile=/opt/georeckon/.env
ExecStart=/opt/georeckon/.venv/bin/python -m georeckon.server.app
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```
Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable georeckon
sudo systemctl start georeckon
sudo systemctl status georeckon
```

---

## 4. Production Nginx Reverse Proxy with SSL (WSS)

To support secure WebSockets (`wss://your-domain.com/socket.io/`), configure Nginx with TLS termination:

### Install Nginx & Certbot
```bash
sudo apt-get install -y nginx certbot python3-certbot-nginx
```

### Configure Nginx Site (`/etc/nginx/sites-available/georeckon`)
```nginx
server {
    server_name api.georeckon.yourdomain.com;

    location /socket.io/ {
        proxy_pass http://127.0.0.1:5000/socket.io/;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }

    location / {
        proxy_pass http://127.0.0.1:5000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

### Enable Site and Obtain SSL Certificate
```bash
sudo ln -s /etc/nginx/sites-available/georeckon /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
sudo certbot --nginx -d api.georeckon.yourdomain.com
```

---

## 5. Health Check & Connectivity Verification

1. **Check Service Logs:**
   ```bash
   journalctl -u georeckon -f
   ```
2. **Test Socket.IO Ping / Pong:**
   ```bash
   python -c "import socketio; sio = socketio.Client(); sio.connect('http://127.0.0.1:5000'); print('Connected:', sio.connected); sio.disconnect()"
   ```
