package io.dsh.android;

/** Exercises callback ordering without requiring an Android device. */
public final class PageLoadStateTest {
    public static void main(String[] args) {
        PageLoadState page = new PageLoadState();
        page.begin();
        check(page.fail(), "main-frame failure must enter failure state");
        check(!page.complete(), "late onPageFinished must not hide a failure");
        check(page.isFailed(), "failure remains visible until an explicit retry");
        page.begin();
        check(page.complete(), "retry can reveal the recovered page");
        check(!page.complete(), "duplicate completion must not repeat boot feedback");
        check(page.isReady(), "successful page remains ready");
        page.begin();
        check(!page.isReady(), "a new navigation resets readiness");
        page.destroy();
        page.begin();
        check(!page.complete() && !page.fail(), "destroyed pages ignore asynchronous callbacks");
        System.out.println("PASS: failure, late finish, retry, duplicate finish, navigation, destruction");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
