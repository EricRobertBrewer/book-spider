import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;
import com.ericrobertbrewer.bookspider.sites.meta.BookCave;
import com.ericrobertbrewer.web.driver.DriverUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.PrintWriter;
import java.util.List;
import java.util.logging.Logger;

public class ReviewScraper {

    static DatabaseHelper db = new DatabaseHelper(Logger.getLogger("string".getClass().getSimpleName()));

    public static void main(String args[]){
        db.connectToContentsDatabase();
        List<BookCave.Book> books = null;
       try {
          books = db.getBookCaveBooks();
       }catch (Exception e){
           System.out.println("BAD SQL");
       }
        System.setProperty("webdriver.chrome.driver", "D:\\PonyStreamer\\src\\chromedriver.exe");
        ChromeDriver driver = new ChromeDriver();

        for(BookCave.Book book: books){


            try {
                driver.navigate().to(book.amazonKindleUrl);

                DriverUtils.scrollDown(driver, 2, 1000);

                WebElement reviews = driver.findElement(By.className("reviews-content"));

                PrintWriter tagfile = new PrintWriter("Reviews/" + book.id + ".txt");
                tagfile.println(reviews.getText());
                tagfile.close();
            } catch (Exception e){

            }

        }
    }



}
