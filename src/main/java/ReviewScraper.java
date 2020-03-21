import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;

import java.util.logging.Logger;

public class ReviewScraper {

    static DatabaseHelper db = new DatabaseHelper(Logger.getLogger("string".getClass().getSimpleName()));

    public static void main(String args[]){
       try {
           db.getBookCaveBooks();
       }catch (Exception e){
           System.out.println("BAD SQL");
       }
    }

}
