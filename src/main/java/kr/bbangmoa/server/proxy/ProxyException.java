package kr.bbangmoa.server.proxy;

/**
 * 프록시가 스스로 판단해서 내는 실패.
 * 상류가 낸 실패와 우리가 낸 실패를 코드에서 구분하기 위해 따로 둔다.
 */
public class ProxyException extends RuntimeException {

    private final int status;

    public ProxyException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
