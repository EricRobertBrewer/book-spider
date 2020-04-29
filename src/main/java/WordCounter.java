import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;
import com.ericrobertbrewer.bookspider.sites.meta.BookCave;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Created by jotbills on 4/7/20.
 */
public class WordCounter {


    static DatabaseHelper db = new DatabaseHelper(Logger.getLogger("string".getClass().getSimpleName()));

    public static void main(String args[]){
        db.connectToContentsDatabase();
        String ignore = "<div </div> <span var <a the &amp;&amp; if to of </a> #&gt; </span> a-row class=\"a-row: &lt;# class=\"a-icon class=\"a-section <td and <script in window.$Nav class=\"a-size-base function" +
                "a-text-normal\" your: display:: </script>: for: type=\"hidden\": class=\"a-link-normal: <input: class=\"a-link-normal\": Book: class=\"a-size-small: width:: return: font-size:: <option:  class=\"a-declarative\":" +
                "this: type=\"text/javascript\">:" ;

        //Note: &amp;&amp; appeared before the on the lower ratings : All Ages - Mild Plus, #&gt; before of and </a> for all ages
        //Higher ratings: moderate+-adult+ had "a-row" before </span> and #&gt, class="a-icon before &lt;#;
        //All ages has &lt;# before a-row, <td before class="a-icon and class="a-section
        //All classes align again at script
        //Higher ratings had class="a-size-base: before assorted elements like function:
        //All except All Ages had Book:, width::, and class="a-size-small:  before return:
        //Lower (2) have type="text/javascript">:  before color::, higher (3) have this: before it
        //Adult+ also has color Kindle: before color::

        try {
           List<BookCave.BookRating> books = db.getBookCaveRatings();
           Map<String, Map<String, Integer>> wordCounts = new HashMap<>();
           for(BookCave.BookRating book : books){
               try {
                   List<String> lines = Files.readAllLines(Paths.get("src/Reviews/" + book.bookId +".txt"));
                   //System.out.println(book.rating);
                   if (!wordCounts.containsKey(book.rating)) wordCounts.put(book.rating, new HashMap<>());
                   Map<String, Integer> count = wordCounts.get(book.rating);
                   for(String line : lines){
                       //line = line.replaceAll("#", "*");
                       line = line.trim();
                       String[] tokens = line.split(" ");
                       for(int i = 0; i < tokens.length; i++){
                           if(tokens[i].matches(".*[a-zA-Z].*") && !ignore.contains(tokens[i])) {
                               if (count.containsKey(tokens[i])) {
                                   count.put(tokens[i], count.get(tokens[i]) + 1);
                               } else {
                                   count.put(tokens[i], 1);
                               }
                           }
                       }
                   }
               }catch (IOException io){
                   //System.out.println(book.bookId);
               }
           }

           //print biggest
           for(String key : wordCounts.keySet()){
               System.out.println(key);
               Map<String, Integer> count = wordCounts.get(key);
               int most = 0;
               String much = "";
               for(String word: count.keySet()){
                  int c = count.get(word);
                  if(c > most){
                      most = c;
                      much = word;
                  }

               }
               System.out.println(much + ": " + most);

           }



            //File reviewFolder = new File("Reviews");
            //for(File review: reviewFolder.listFiles()){

                //read data, count
            //}

        }catch (SQLException e){
            System.out.println("BAD SQL");
        }



    }

}

