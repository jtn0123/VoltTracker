package android.webkit;

/**
 * Test-only {@link WebResourceError}: the platform constructor is package-private, so unit tests
 * that need to drive {@code WebViewClient.onReceivedError} construct this same-package subclass.
 */
public class TestWebResourceError extends WebResourceError {
    private final int code;
    private final String description;

    public TestWebResourceError(int code, String description) {
        this.code = code;
        this.description = description;
    }

    @Override
    public int getErrorCode() {
        return code;
    }

    @Override
    public CharSequence getDescription() {
        return description;
    }
}
