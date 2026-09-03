#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# 우분투 서버 초기 세팅 — 런북 단계 6·7·8
#
# 쓰는 법:
#   서버에 SSH로 들어가서 아래를 순서대로 실행한다.
#     curl -fsSL https://raw.githubusercontent.com/vmgfh878-art/bbangmoa-server/main/scripts/server-setup.sh -o setup.sh
#     less setup.sh          # ← 남의 스크립트는 실행 전에 반드시 읽는다
#     bash setup.sh
#
# 안 하는 것:
#   SSH 비밀번호 로그인 차단은 여기 넣지 않았다. 잘못되면 서버에 못 들어가는데,
#   스크립트로 자동 실행하면 확인할 틈이 없기 때문이다. 맨 아래 안내대로 손으로 한다.
#
# 어느 업체든 우분투면 그대로 동작한다 (AWS / Vultr / Oracle / 집 서버).
# ─────────────────────────────────────────────────────────────

set -euo pipefail
# set -e : 명령 하나라도 실패하면 즉시 중단 (실패를 못 보고 지나치지 않게)
# set -u : 정의 안 된 변수를 쓰면 중단
# set -o pipefail : 파이프 중간이 실패해도 전체를 실패로 본다

step() { echo; echo "━━━ $* ━━━"; }

step "0. 이 서버 정보"
echo "호스트명 : $(hostname)"
echo "아키텍처 : $(dpkg --print-architecture)   # arm64면 t4g 계열, amd64면 x86"
echo "우분투   : $(lsb_release -ds 2>/dev/null || echo '확인 불가')"
echo "메모리   : $(free -h | awk '/^Mem:/{print $2}')"


step "1. 패키지 최신화"
# 보안 패치가 밀려 있는 상태로 서버를 여는 건 위험하다.
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get upgrade -y


step "2. 한국 시간대"
# 기본은 UTC라 로그 시각이 9시간 어긋난다.
# 장애 시각과 로그를 대조하지 못하게 되는 게 진짜 문제다.
sudo timedatectl set-timezone Asia/Seoul
date


step "3. 스왑 2GB"
# RAM이 부족하면 리눅스는 OOM Killer로 프로세스를 죽인다. 보통 우리 자바 앱이다.
# 스왑은 디스크 일부를 느린 RAM처럼 빌려 쓰는 것 — 성능이 아니라 생존용이다.
# RAM 2GB에 JVM+DB+Redis를 얹으면 부팅 순간이 제일 위험하다.
if swapon --show | grep -q '/swapfile'; then
  echo "이미 스왑이 있음 — 건너뜀"
else
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile          # 소유자만 읽기/쓰기. 스왑에는 메모리 내용이 들어간다
  sudo mkswap /swapfile
  sudo swapon /swapfile
  # fstab에 적어야 재부팅 후에도 유지된다
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
fi
free -h


step "4. 방화벽 (ufw) — OS쪽 문"
# 클라우드 보안그룹이 건물 정문이면 이건 집 현관문. 둘 다 열려야 통한다.
sudo ufw default deny incoming     # 기본은 전부 차단
sudo ufw default allow outgoing    # 나가는 건 허용 (apt, docker pull 등)
sudo ufw allow 22/tcp              # ← enable 전에 반드시 먼저. 안 그러면 스스로를 잠근다
sudo ufw allow 80/tcp              # 인증서 발급 + https로 넘겨주기
sudo ufw allow 443/tcp             # 실제 서비스
# 8080은 열지 않는다. Nginx가 앞에 서고, 앱 포트는 밖에 내지 않는다.
sudo ufw --force enable
sudo ufw status verbose


step "5. fail2ban — 반복 실패 IP 자동 차단"
# 공인 IP가 붙는 순간부터 전 세계가 SSH 로그인을 시도한다. 과장이 아니다.
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y fail2ban
sudo systemctl enable --now fail2ban
sudo systemctl is-active fail2ban


step "6. 도커 설치 (공식 절차)"
if command -v docker > /dev/null 2>&1; then
  echo "이미 설치됨 — 건너뜀: $(docker --version)"
else
  sudo apt-get install -y ca-certificates curl gnupg
  sudo install -m 0755 -d /etc/apt/keyrings
  # 도커 공식 서명키. 이게 있어야 apt가 "진짜 도커가 만든 패키지"임을 검증한다
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  sudo chmod a+r /etc/apt/keyrings/docker.gpg
  # $(dpkg --print-architecture)가 arm64/amd64를 알아서 넣어준다 → 어느 서버든 그대로 동작
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
  sudo apt-get update
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  # sudo 없이 docker를 쓰려면 docker 그룹에 들어가야 한다
  sudo usermod -aG docker "$USER"
fi

# 재부팅해도 도커가 자동으로 켜지게 (= 컨테이너도 자동으로 뜨게)
sudo systemctl enable docker
echo "docker 자동시작: $(systemctl is-enabled docker)"


step "완료"
cat <<'GUIDE'

확인할 것
  date                      → KST 로 나오는가
  free -h                   → Swap 이 2.0Gi 인가
  sudo ufw status           → 22, 80, 443 만 있는가
  systemctl is-enabled docker → enabled 인가

지금 바로 할 것
  1) 로그아웃 후 다시 접속한다. docker 그룹 권한이 그때 적용된다.
  2) 확인:  docker run hello-server 대신 →  docker run hello-world

--------------------------------------------------------------------
아직 안 한 것: SSH 비밀번호 로그인 차단
--------------------------------------------------------------------
이것만 손으로 한다. 잘못 저장하고 세션을 닫으면 서버에 못 들어간다.

  sudo nano /etc/ssh/sshd_config

아래 세 줄을 찾아 이렇게 맞춘다. 앞에 # 이 있으면 지운다.

  PasswordAuthentication no
  PermitRootLogin no
  PubkeyAuthentication yes

저장(Ctrl+O, Enter) 후 종료(Ctrl+X), 그리고

  sudo systemctl restart ssh

★ 지금 터미널을 절대 닫지 말 것.
  새 터미널을 하나 더 열어 접속이 되는지 확인한 다음에만 원래 창을 닫는다.
  실패해도 클라우드 콘솔의 시리얼 콘솔로 복구할 수 있지만, 안 겪는 게 낫다.
GUIDE
