package io.mosire.bma3.webconnect;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;

/** Selenium-backed Chromium session. ChromeDriver discovery is delegated to Selenium Manager. */
public final class SeleniumBrowserSession implements BrowserSession {

    private static final String SNAPSHOT_JS = """
            const nodes = Array.from(document.querySelectorAll(
              'a,button,input,textarea,select,[role="button"],[contenteditable="true"]'
            )).filter((el) => {
              const r = el.getBoundingClientRect();
              const s = getComputedStyle(el);
              return r.width > 0 && r.height > 0
                && s.visibility !== 'hidden' && s.display !== 'none';
            }).slice(0, 80);
            nodes.forEach((el, i) => el.setAttribute('data-bma3-ref', 'bma3-' + (i + 1)));
            return nodes.map((el, i) => {
              const r = el.getBoundingClientRect();
              return {
                ref: 'bma3-' + (i + 1),
                tag: el.tagName.toLowerCase(),
                text: (el.innerText || el.value || el.getAttribute('aria-label') || '')
                  .trim().slice(0, 180),
                href: el.href || '',
                type: el.getAttribute('type') || '',
                x: Math.round(r.x), y: Math.round(r.y),
                width: Math.round(r.width), height: Math.round(r.height)
              };
            });
            """;

    private final WebDriver driver;
    private final JavascriptExecutor javascript;
    private final Duration pageLoadTimeout;

    public SeleniumBrowserSession(BrowserOptions options) {
        try {
            Files.createDirectories(options.userDataDir());
            Files.createDirectories(options.screenshotDir());
        } catch (IOException failure) {
            throw new IllegalStateException("创建浏览器目录失败", failure);
        }
        ChromeOptions chrome = new ChromeOptions();
        chrome.setBinary(options.binary());
        chrome.addArguments("--disable-gpu", "--no-first-run", "--no-default-browser-check",
                "--disable-dev-shm-usage", "--window-size=1280,900",
                "--user-data-dir=" + options.userDataDir().toAbsolutePath());
        if (options.headless()) {
            chrome.addArguments("--headless=new");
        }
        this.pageLoadTimeout = options.pageLoadTimeout();
        try {
            String driverPath = System.getenv("BMA3_CHROMEDRIVER");
            if (driverPath != null && !driverPath.isBlank()) {
                ChromeDriverService service = new ChromeDriverService.Builder()
                        .usingDriverExecutable(Path.of(driverPath).toFile())
                        .build();
                this.driver = new ChromeDriver(service, chrome);
            } else {
                this.driver = new ChromeDriver(chrome);
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("启动 Chromium/ChromeDriver 失败: "
                    + failure.getMessage(), failure);
        }
        this.javascript = (JavascriptExecutor) driver;
        driver.manage().timeouts().pageLoadTimeout(pageLoadTimeout);
    }

    @Override
    public void open(String url) {
        driver.get(url);
        waitForReady();
    }

    @Override
    public String title() {
        return driver.getTitle();
    }

    @Override
    public String currentUrl() {
        return driver.getCurrentUrl();
    }

    @Override
    public List<ElementRef> snapshot() {
        Object raw = javascript.executeScript(SNAPSHOT_JS);
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        List<ElementRef> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            result.add(new ElementRef(
                    text(map.get("ref")),
                    text(map.get("tag")),
                    text(map.get("text")),
                    text(map.get("href")),
                    text(map.get("type")),
                    number(map.get("x")),
                    number(map.get("y")),
                    number(map.get("width")),
                    number(map.get("height"))));
        }
        return List.copyOf(result);
    }

    @Override
    public void click(String ref) {
        var before = driver.getWindowHandles();
        Object clicked = javascript.executeScript("""
                const el = document.querySelector('[data-bma3-ref="' + arguments[0] + '"]');
                if (!el) return false;
                el.scrollIntoView({block: 'center'});
                el.click();
                return true;
                """, ref);
        if (!Boolean.TRUE.equals(clicked)) {
            throw new IllegalArgumentException("元素不存在: " + ref);
        }
        try {
            Thread.sleep(300);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        for (String handle : driver.getWindowHandles()) {
            if (!before.contains(handle)) {
                driver.switchTo().window(handle);
                break;
            }
        }
        waitForReady();
    }

    @Override
    public void type(String ref, String value) {
        Object changed = javascript.executeScript("""
                const el = document.querySelector('[data-bma3-ref="' + arguments[0] + '"]');
                if (!el) return false;
                el.scrollIntoView({block: 'center'});
                el.focus();
                if ('value' in el) el.value = arguments[1];
                else el.textContent = arguments[1];
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                return true;
                """, ref, value);
        if (!Boolean.TRUE.equals(changed)) {
            throw new IllegalArgumentException("元素不存在: " + ref);
        }
    }

    @Override
    public String text() {
        Object raw = javascript.executeScript(
                "return document.body ? document.body.innerText : '';");
        return raw == null ? "" : raw.toString();
    }

    @Override
    public double videoDuration() {
        Object raw = javascript.executeScript("""
                const v = Array.from(document.querySelectorAll('video'))
                  .find((el) => el.offsetWidth > 0 && el.offsetHeight > 0);
                return v && Number.isFinite(v.duration) ? v.duration : 0;
                """);
        return raw instanceof Number number ? number.doubleValue() : 0.0;
    }

    @Override
    public Path frameAt(double seconds, Path directory) {
        driver.manage().timeouts().scriptTimeout(pageLoadTimeout);
        Object seeked = ((JavascriptExecutor) driver).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                const v = Array.from(document.querySelectorAll('video'))
                  .find((el) => el.offsetWidth > 0 && el.offsetHeight > 0);
                if (!v) { done(false); return; }
                v.pause();
                const target = Math.max(0, Math.min(arguments[0], v.duration || arguments[0]));
                const finish = () => setTimeout(() => done(true), 150);
                if (Math.abs(v.currentTime - target) < 0.05 && v.readyState >= 2) {
                  finish();
                  return;
                }
                const timer = setTimeout(finish, 3000);
                v.addEventListener('seeked', () => { clearTimeout(timer); finish(); }, {once: true});
                v.currentTime = target;
                """, seconds);
        if (!Boolean.TRUE.equals(seeked)) {
            throw new IllegalStateException("页面没有可用的 HTML5 video");
        }
        try {
            Files.createDirectories(directory);
            WebElement video = driver.findElements(By.cssSelector("video")).stream()
                    .filter(WebElement::isDisplayed)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("video 元素不可见"));
            File file = video.getScreenshotAs(OutputType.FILE);
            Path target = directory.resolve("video-frame-" + Instant.now().toEpochMilli() + ".png");
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException failure) {
            throw new IllegalStateException("保存视频帧失败", failure);
        }
    }

    @Override
    public Path screenshot(Path directory) {
        try {
            Files.createDirectories(directory);
            File file = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Path target = directory.resolve("browser-" + Instant.now().toEpochMilli() + ".png");
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException failure) {
            throw new IllegalStateException("保存截图失败", failure);
        }
    }

    private void waitForReady() {
        if (pageLoadTimeout.isZero() || pageLoadTimeout.isNegative()) {
            return;
        }
        long deadline = System.nanoTime() + pageLoadTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            Object state = javascript.executeScript("return document.readyState");
            if ("complete".equals(state)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    @Override
    public void close() {
        driver.quit();
    }
}
