# book-spider

Extract excerpts of literature.

## Usage

1. Download a WebDriver. I used [ChromeDriver](https://chromedriver.chromium.org/downloads).

2. Run `com.ericrobertbrewer.bookspider.sites.meta.BookCave` with the VM option: `-Dwebdriver.chrome.driver=C:\Users\Me\Path\To\My\webdriver.exe` optionally using program arguments: `[threads=1] [max-retries=1]`.

For example:
```
java -Dwebdriver.chrome.driver=C:\Users\Me\Path\To\My\webdriver.exe com.ericrobertbrewer.bookspider.sites.meta.BookCave 3 3
```

This will only scrape the meta data for books in [BookCave](https://mybookcave.com/mybookratings/).

3. Create an Amazon account. Sign up for [Kindle Unlimited](https://www.amazon.com/kindle-dbs/hz/subscribe/ku?*entries*=0&_encoding=UTF8&*Version*=1&shoppingPortalEnabled=true). You will have to provide your credit card information, but the free trail lasts for 30 days (set a calendar reminder to cancel your subscription later!).

4. Using an IDE like [IntelliJ](https://www.jetbrains.com/idea/), set a breakpoint in `com.ericrobertbrewer.bookspider.sites.text.AmazonKindle` in the `signIn()` method _below_ the line `continueInput.click();`. **Debug** `com.ericrobertbrewer.bookspider.sites.meta.BookCave.AmazonKindleProvider` with the same WebDriver path as a VM option and your Amazon account credentials.

For example:

Main class: `com.ericrobertbrewer.bookspider.sites.meta.BookCave.AmazonKindleProvider`

VM options: `-Dwebdriver.chrome.driver=C:\Users\Me\Path\To\My\webdriver.exe`

Program arguments: `-m book -e myamazonemail@mymail.com -p mYaMaZoNpAsSwOrD -f Bob -t 4 -r 4`

When the program hits the breakpoint, check your email for an OTP usually containing the subject 'Amazon Authentication'. Enter the OTP in the program-controlled browser window, then click 'Continue'. In the IDE, hit the 'Resume Program' button. You will have to enter the OTP for every browser window. After you've entered each OTP, remove the breakpoint.

5. Wait. You may need to repeat step 4 occasionally.

6. Enjoy!
