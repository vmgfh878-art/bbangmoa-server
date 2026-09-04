#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
# Nginx 리버스 프록시 + HTTPS — 런북 단계 9·10
#
# 쓰는 법: 서버에 SSH로 들어가서
#   curl -fsSL https://raw.githubusercontent.com/vmgfh878-art/bbangmoa-server/main/scripts/nginx-tls-setup.sh -o nginx.sh
#   less nginx.sh                          # ← 남의 스크립트는 실행 전에 반드시 읽는다
#   bash nginx.sh api.내도메인.com
#
# 이게 하는 일
#   지금 8080은 서버 자기 자신한테만 열려 있다 (docker-compose.yml 의 127.0.0.1:8080).
#   밖에서 들어온 443 요청을 받아 그 8080 으로 넘겨주는 중개인을 세운다.
#   Vercel 이 네 dist/ 를 대신 서빙해주던 자리에, 여기서는 Nginx 가 선다.
#
# 왜 컨테이너가 아니라 서버에 직접 설치하나
#   배포할 때마다 deploy.yml 이 docker compose up -d 를 돌린다.
#   프록시까지 컨테이너면 앱을 새로 올릴 때마다 문 자체가 잠깐 닫힌다.
#   앱은 갈아끼워도 문은 계속 서 있어야 한다.
#
# 인증서는 Cloudflare Origin Certificate 를 쓴다 (Lets Encrypt 아님)
#   유효기간이 15년이라 "자동갱신이 조용히 실패해서 https 가 깨진다"는 장애가 없다.
#   대신 이 인증서는 Cloudflare 만 신뢰한다. 즉 DNS 의 주황 구름(프록시)이
#   켜져 있어야 하고, 회색 구름으로 바꾸면 브라우저가 인증서 오류를 낸다.
#   0단계에서 그걸 검사한다.
#
# 여러 번 돌려도 안전하다 (같은 결과로 수렴한다).
#
# ⚠ 2026-09-03 기준 현재 상태 — 아직 이 스크립트를 돌린 적이 없다.
#   지금 서버는 certbot(Let's Encrypt) + Cloudflare 회색 구름(DNS only)으로 돼 있다
#   (nginx/api.conf, 인증서는 /etc/letsencrypt/live/api.breadmoa.com/).
#   이 스크립트를 돌리면 그 위에 사이트 설정을 덮어쓰고 주황 구름(Proxied)이
#   필요해진다 — 실행 전 nginx/api.conf 와의 관계를 다시 확인할 것.
# ─────────────────────────────────────────────────────────────

set -euo pipefail

CERT_DIR="/etc/ssl/cloudflare"
CERT_FILE="$CERT_DIR/origin.pem"
KEY_FILE="$CERT_DIR/origin.key"
APP_UPSTREAM="127.0.0.1:8080"

step() { echo; echo "━━━ $* ━━━"; }
die()  { echo; echo "✗ $*" >&2; echo; exit 1; }

DOMAIN="${1:-}"
[ -n "$DOMAIN" ] || die "도메인을 인자로 넘겨라.   예:  bash $0 api.내도메인.com"


step "0. 사전 확인"

# ── 인증서가 서버에 있는가 ──
# 이 두 파일은 Cloudflare 웹 화면에서 만들어 복사해 붙이는 것이라 자동화할 수 없다.
# 없으면 여기서 멈추고 만드는 방법을 알려준다.
if [ ! -s "$CERT_FILE" ] || [ ! -s "$KEY_FILE" ]; then
  cat <<GUIDE

인증서가 아직 없다. 먼저 Cloudflare 에서 만들어야 한다.

[브라우저에서]
  1) dash.cloudflare.com → 도메인 선택 → 왼쪽 메뉴 SSL/TLS → Origin Server
  2) "Create Certificate" 클릭
  3) Private key type : RSA (2048)          ← 기본값 그대로
     Hostnames        : $DOMAIN
                        (나중에 다른 서브도메인도 쓸 거면 *.내도메인.com 도 함께)
     Certificate Validity : 15 years
  4) Create 를 누르면 텍스트 상자가 두 개 나온다.

  ★ 아래쪽 Private Key 는 이 화면을 닫으면 다시 볼 수 없다.
    닫기 전에 두 개를 모두 서버에 붙여넣어야 한다.

[서버 터미널에서] 아래를 그대로 실행하고, 각각 내용을 붙여넣은 뒤 Ctrl+D 를 누른다.

  sudo mkdir -p $CERT_DIR

  # ① 위쪽 상자(Origin Certificate) 내용 → 붙여넣고 Ctrl+D
  sudo tee $CERT_FILE > /dev/null

  # ② 아래쪽 상자(Private Key) 내용 → 붙여넣고 Ctrl+D
  sudo tee $KEY_FILE > /dev/null

  # 권한. 개인키는 소유자만 읽을 수 있어야 한다 — 이게 곧 이 도메인의 신분증이다.
  sudo chown root:root $CERT_FILE $KEY_FILE
  sudo chmod 644 $CERT_FILE
  sudo chmod 600 $KEY_FILE

그리고 이 스크립트를 다시 실행한다:
  bash $0 $DOMAIN

GUIDE
  die "인증서 파일 없음 — 위 안내를 먼저 수행할 것"
fi
echo "인증서   : 있음 ($CERT_FILE)"

# ── 인증서와 개인키가 서로 맞는 짝인가 ──
# 다른 도메인의 키를 붙여넣는 실수가 흔하다. 그냥 두면 Nginx 를 재시작할 때야 알게 된다.
if command -v openssl > /dev/null 2>&1; then
  c_mod=$(sudo openssl x509 -noout -modulus -in "$CERT_FILE" 2>/dev/null | openssl md5)
  k_mod=$(sudo openssl rsa  -noout -modulus -in "$KEY_FILE"  2>/dev/null | openssl md5)
  [ "$c_mod" = "$k_mod" ] || die "인증서와 개인키가 짝이 맞지 않는다. 두 상자를 다시 복사해 붙일 것."
  echo "키 짝맞춤: OK"
  echo "만료      : $(sudo openssl x509 -noout -enddate -in "$CERT_FILE" | cut -d= -f2)"
fi

# ── DNS 가 프록시(주황 구름)를 거치고 있는가 ──
# Origin Certificate 는 Cloudflare 만 신뢰한다.
# 회색 구름이면 브라우저가 서버에 직접 붙어서 인증서 오류를 낸다.
resolved=$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1}' | head -1 || true)
my_ip=$(curl -fsS -4 --max-time 5 https://api.ipify.org 2>/dev/null || true)

if [ -z "$resolved" ]; then
  die "$DOMAIN 이 아직 아무 IP 로도 풀리지 않는다.
     Cloudflare DNS 에 A 레코드를 먼저 추가할 것 ($DOMAIN → 서버의 탄력적 IP).
     방금 추가했다면 몇 분 기다린 뒤 다시 실행."
fi
echo "DNS 응답 : $DOMAIN → $resolved"

if [ -n "$my_ip" ] && [ "$resolved" = "$my_ip" ]; then
  cat <<WARN

⚠ DNS 가 이 서버 IP 를 그대로 내주고 있다 = Cloudflare 프록시가 꺼진 상태(회색 구름).
  이 상태로는 브라우저가 "안전하지 않음" 경고를 낸다. Origin Certificate 는
  Cloudflare 만 신뢰하기 때문이다.

  Cloudflare → DNS → 해당 A 레코드의 구름 아이콘을 눌러 주황색(Proxied)으로 바꿀 것.

WARN
  read -r -p "그래도 계속할까? (y/N) " ans
  [ "$ans" = "y" ] || die "중단했다. 구름을 주황색으로 바꾼 뒤 다시 실행할 것."
else
  echo "프록시   : 켜짐으로 보임 (응답 IP 가 서버 IP 와 다름 = Cloudflare 를 거친다)"
fi


step "1. Nginx 설치"
if command -v nginx > /dev/null 2>&1; then
  echo "이미 설치됨 — 건너뜀: $(nginx -v 2>&1)"
else
  sudo apt-get update
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y nginx
fi


step "2. 실제 방문자 IP 복원"
# 프록시를 쓰면 Nginx 눈에는 모든 요청이 Cloudflare 에서 온 것으로 보인다.
# 로그가 전부 Cloudflare IP 로 도배되고, 나중에 IP 기준 차단·집계가 불가능해진다.
# Cloudflare 는 진짜 방문자 IP 를 CF-Connecting-IP 헤더에 담아 보내주는데,
# "이 대역에서 온 요청이면 그 헤더를 믿어라"고 알려줘야 쓸 수 있다.
if cf4=$(curl -fsS --max-time 10 https://www.cloudflare.com/ips-v4) \
   && cf6=$(curl -fsS --max-time 10 https://www.cloudflare.com/ips-v6); then
  {
    echo "# Cloudflare 대역 — nginx-tls-setup.sh 가 생성. 손으로 고치지 말 것."
    echo "# 갱신하려면 스크립트를 다시 실행한다."
    printf '%s\n' "$cf4" | sed '/^$/d; s|^|set_real_ip_from |; s|$|;|'
    printf '%s\n' "$cf6" | sed '/^$/d; s|^|set_real_ip_from |; s|$|;|'
    echo "real_ip_header CF-Connecting-IP;"
  } | sudo tee /etc/nginx/conf.d/cloudflare-realip.conf > /dev/null
  echo "대역 $(printf '%s\n' "$cf4" "$cf6" | sed '/^$/d' | wc -l)개 등록"
else
  echo "경고: Cloudflare IP 목록을 받지 못했다. 이 단계는 건너뛴다."
  echo "      서비스 동작에는 지장이 없고, 로그의 IP 가 Cloudflare 것으로 찍힐 뿐이다."
fi


step "3. 서버 블록 작성"
# server_name 이 곧 "어떤 도메인으로 온 요청을 이 설정으로 처리할지"다.
# 아래 heredoc 은 따옴표를 안 걸었으므로 $DOMAIN 같은 셸 변수는 값으로 치환되고,
# Nginx 자신의 변수($host 등)는 \$ 로 적어 그대로 남긴다.
sudo tee /etc/nginx/sites-available/bbangmoa > /dev/null <<CONF
# nginx-tls-setup.sh 가 생성 — 도메인: $DOMAIN

# HTTP 로 온 요청은 HTTPS 로 돌려보낸다.
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    return 301 https://\$host\$request_uri;
}

# 도메인 없이 IP 로 직접 찔러보는 스캐너에게는 아무것도 주지 않는다.
# 444 는 Nginx 전용 코드로 "응답 없이 연결을 끊는다"는 뜻이다.
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    return 444;
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name $DOMAIN;

    # HTTP/2 는 켜지 않는다. 브라우저와의 연결은 Cloudflare 가 담당하므로
    # Cloudflare↔이 서버 구간이 HTTP/1.1 이어도 체감 차이가 없다.

    ssl_certificate     $CERT_FILE;
    ssl_certificate_key $KEY_FILE;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;

    # 업로드 상한. 기본값 1MB 는 이미지 한 장에도 413 이 난다.
    client_max_body_size 10m;

    # 서버가 무엇인지 버전까지 알려줄 이유가 없다.
    server_tokens off;

    access_log /var/log/nginx/bbangmoa.access.log;
    error_log  /var/log/nginx/bbangmoa.error.log;

    location / {
        proxy_pass http://$APP_UPSTREAM;
        proxy_http_version 1.1;

        # 이 네 줄이 없으면 스프링은 모든 요청이 127.0.0.1 에서 http 로
        # 들어온 것으로 안다. 리다이렉트 주소가 http 로 나가고,
        # 접속자 IP 가 전부 127.0.0.1 로 기록된다.
        # application.yaml 의 forward-headers-strategy: framework 가 이걸 읽는다.
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;

        proxy_connect_timeout 5s;
        proxy_read_timeout    60s;
    }

    # 헬스체크는 로그에 남기지 않는다.
    # Dockerfile 의 HEALTHCHECK 가 15초마다 도는데 그것까지 찍으면
    # 로그가 이것만으로 채워져 정작 볼 게 안 보인다.
    location = /actuator/health {
        proxy_pass http://$APP_UPSTREAM/actuator/health;
        proxy_set_header Host \$host;
        access_log off;
    }
}
CONF

# sites-available 은 "써둔 설정", sites-enabled 는 "실제로 켠 설정"이다.
# 링크로 켠다 — 껐다 켜기가 파일 삭제 없이 되기 때문에 이렇게 나눠져 있다.
sudo ln -sfn /etc/nginx/sites-available/bbangmoa /etc/nginx/sites-enabled/bbangmoa

# 설치 시 딸려오는 기본 사이트를 끈다.
# 남겨두면 default_server 가 둘이 되어 Nginx 가 시작을 거부한다.
sudo rm -f /etc/nginx/sites-enabled/default
echo "작성: /etc/nginx/sites-available/bbangmoa"


step "4. 문법 검사 후 반영"
# reload 는 재시작이 아니다. 기존 연결을 끊지 않고 새 설정을 적용한다.
# 검사를 먼저 하는 이유: 설정이 깨진 상태로 restart 하면 Nginx 가 아예 안 뜬다.
sudo nginx -t
sudo systemctl reload nginx 2>/dev/null || sudo systemctl restart nginx
sudo systemctl enable nginx > /dev/null 2>&1 || true
echo "nginx: $(systemctl is-active nginx) / 자동시작 $(systemctl is-enabled nginx)"


step "5. 서버 안에서 자체 확인"
# 밖에서 되는지는 브라우저로 봐야 하지만, 안에서부터 막혀 있으면
# Cloudflare·보안그룹을 아무리 만져도 안 된다. 안쪽부터 좁혀 들어간다.
echo -n "8080 스프링 직접 : "
curl -fsS --max-time 5 "http://$APP_UPSTREAM/actuator/health" \
  || echo "실패 — 앱이 안 떠 있다.  cd ~/app/bbangmoa && docker compose ps"
echo
echo -n "443 Nginx 경유   : "
# --resolve 로 DNS 를 무시하고 이 서버 자신에게 붙는다.
# Origin 인증서는 Cloudflare 만 신뢰하므로 -k 로 검증을 건너뛴다.
curl -fsS -k --max-time 5 --resolve "$DOMAIN:443:127.0.0.1" "https://$DOMAIN/actuator/health" \
  || echo "실패 — /var/log/nginx/bbangmoa.error.log 확인"
echo


step "완료"
cat <<GUIDE

이제 브라우저에서 확인한다.

  https://$DOMAIN/actuator/health   →  {"status":"UP"} 이 뜨면 끝이다.

안 되면 순서대로:

  1) Cloudflare → SSL/TLS → Overview 의 암호화 모드가 "Full (strict)" 인가
     ★ 여기가 제일 많이 걸린다.
       Flexible 이면 Cloudflare→서버 구간이 http 라서 무한 리다이렉트가 돈다
       (ERR_TOO_MANY_REDIRECTS). Full (strict) 로 바꿀 것.

  2) AWS 보안그룹 인바운드에 80, 443 이 열려 있는가
     ufw(서버 안 방화벽)는 server-setup.sh 가 이미 열었지만 그건 집 현관문이고,
     보안그룹은 건물 정문이다. 둘 다 열려야 통한다.

  3) 위 5단계의 "8080 스프링 직접"이 실패했다면 Nginx 문제가 아니라 앱 문제다.
       cd ~/app/bbangmoa && docker compose ps
       docker logs --tail 50 bbangmoa-api

설정을 바꾼 뒤에는 항상:
  sudo nginx -t && sudo systemctl reload nginx
GUIDE
