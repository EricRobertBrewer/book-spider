import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;
import com.ericrobertbrewer.bookspider.sites.meta.BookCave;



import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Created by jotbills on 4/7/20.
 */
public class WordCounter {


    static DatabaseHelper db = new DatabaseHelper(Logger.getLogger("string".getClass().getSimpleName()));

    public static void main(String args[]){

         Map<String, Map<String, Integer>> wordCounts =  getCounts();

           //for each threshold. calculate ratio of word in and out of group
           for(int i = 1; i < 7; i++){
               getMaxRat(i, 1, wordCounts);
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




    }

    public static Map<String, Map<String, Integer>> getCounts() {
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
                    List<String> lines = Files.readAllLines(Paths.get("Reviews/" + book.bookId +".txt"));
                    //System.out.println(book.rating);
                    if (!wordCounts.containsKey(book.rating)) wordCounts.put(book.rating, new HashMap<>());
                    Map<String, Integer> count = wordCounts.get(book.rating);
                    for(String line : lines){
                        //line = line.replaceAll("#", "*");
                        line = line.trim();
                        Set<String> recorded = new HashSet<>();
                        String[] tokens = line.split(" ");
                        for(int i = 0; i < tokens.length; i++){
                            if(tokens[i].matches(".*[a-zA-Z].*") && !ignore.contains(tokens[i]) && !recorded.contains(tokens[i])) {
                                recorded.add(tokens[i]);
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
            return wordCounts;
        }catch (SQLException e){
            System.out.println("BAD SQL");
            return  null;
        }

    }

    public static Borderlines getMaxRat(int threshold, int num, Map<String, Map<String, Integer>> wordCounts){
        String[] levels = {"All Ages", "Mild", "Mild+", "Moderate", "Moderate+", "Adult", "Adult+"  };
        Map<String, Integer> posscores;
        Map<String, Integer> negscores;
        posscores = new HashMap();
        negscores = new HashMap();
        String head = "";
        for(int i = 0; i < threshold; i++){
            head += levels[i] + " ";
            addCounts(wordCounts.get(levels[i]), posscores);
        }
        String foot = "";
        for(int i = threshold; i < 7; i++){
            foot += " " + levels[i];
            addCounts(wordCounts.get(levels[i]), negscores);
        }
        //double most = 0;
        //String best = "";
        String[] heads = new String[num];
        double[] max = new double[num];
        for(String word: posscores.keySet()){
            double ratio = 0;
            if(negscores.containsKey(word)){
                ratio = (1.0 *posscores.get(word))/negscores.get(word);
            } else {
                ratio = posscores.get(word);
            }
            if(ratio > max[num - 1]){
                max[num - 1] = ratio;
                heads[num - 1] = word;
                for(int i = num - 1; i > 0; i-- ){
                    if(max[i] > max[i - 1] ){
                        max[i] = max[i - 1];
                        heads[i] = heads[i-1];
                        max[i - 1] = ratio;
                        heads[i - 1] = word;
                    } else break;
                }
            }
        }
        if(max[num - 1] < 1){
            for(String word: negscores.keySet()){
                if(!posscores.containsKey(word)){
                    double ratio = 1.0/negscores.get(word);
                    if(ratio > max[num - 1]){
                        max[num - 1] = ratio;
                        heads[num - 1] = word;
                        for(int i = num - 1; i > 0; i-- ){
                            if(max[i] > max[i - 1] ){
                                max[i] = max[i - 1];
                                heads[i] = heads[i-1];
                                max[i - 1] = ratio;
                                heads[i - 1] = word;
                            } else break;
                        }
                    }
                }
            }
        }
        //double least = most;
        //String worst = "";
        String[] feet = new String[num];
        double[] min = new double[num];
        for(int i = 0; i < num; i++){
            min[i] = max[0];
        }
        for(String word: negscores.keySet()){
            double ratio = max[0];
            /*if(word.equals("\"his\"")){
                System.out.println("stop");
            }*/
            if(posscores.containsKey(word)){
                ratio = (1.0 *posscores.get(word))/negscores.get(word);
            } else {
                ratio = 1.0/negscores.get(word);
            }
            if(ratio < min[num - 1]){
                min[num - 1] = ratio;
                feet[num - 1] = word;
                for(int i = num - 1; i > 0; i-- ){
                    if(min[i] < min[i - 1] ){
                        min[i] = min[i - 1];
                        feet[i] = feet[i-1];
                        min[i - 1] = ratio;
                        feet[i - 1] = word;
                    } else break;
                }
            }
        }
        if(min[num - 1] >= 1){
            for(String word: posscores.keySet()){
                if(!negscores.containsKey(word)){
                    double ratio = posscores.get(word);
                    if(ratio < min[num - 1]){
                        min[num - 1] = ratio;
                        feet[num - 1] = word;
                        for(int i = num - 1; i > 0; i-- ){
                            if(min[i] < min[i - 1] ){
                                min[i] = min[i - 1];
                                feet[i] = feet[i-1];
                                min[i - 1] = ratio;
                                feet[i - 1] = word;
                            } else break;
                        }
                    }
                }
            }
        }
        System.out.println(head + "|" + foot + ": " + heads[0] + " - " + max[0] + " | " + feet[0] + " - " + min[0] );
        return new Borderlines(heads, feet, max, min);


    }

    private static void addCounts(Map<String, Integer> wc, Map<String, Integer> scores ){
        for(String word: wc.keySet()){
            if(scores.containsKey(word)){
                scores.put(word, scores.get(word) + wc.get(word));
            } else {
                scores.put(word, wc.get(word) + 1);
            }
        }
    }

}

//first results
/*
All Ages | Mild Mild+ Moderate Moderate+ Adult Adult+: Pibbin - 69.0
All Ages Mild | Mild+ Moderate Moderate+ Adult Adult+: Pibbin - 69.0
All Ages Mild Mild+ | Moderate Moderate+ Adult Adult+: Pibbin - 69.0
All Ages Mild Mild+ Moderate | Moderate+ Adult Adult+: mara - 92.0
All Ages Mild Mild+ Moderate Moderate+ | Adult Adult+: Cinkoske - 151.0
All Ages Mild Mild+ Moderate Moderate+ Adult | Adult+: recipes - 639.0

Note to probably find some way to filter out proper nouns, and to find words on the other side

All Ages | Mild Mild+ Moderate Moderate+ Adult Adult+: Pibbin - 69.0 | sexy - 0.0021929824561403508
stop
All Ages Mild | Mild+ Moderate Moderate+ Adult Adult+: Pibbin - 69.0 | Zombie - 0.004830917874396135
stop
All Ages Mild Mild+ | Moderate Moderate+ Adult Adult+: Pibbin - 69.0 | C.T. - 0.010101010101010102
stop
All Ages Mild Mild+ Moderate | Moderate+ Adult Adult+: mara - 92.0 | Tubby - 0.017857142857142856
stop
All Ages Mild Mild+ Moderate Moderate+ | Adult Adult+: Cinkoske - 151.0 | Emmalin - 0.027777777777777776
stop
All Ages Mild Mild+ Moderate Moderate+ Adult | Adult+: recipes - 639.0 | Emmalin - 0.027777777777777776
 */

/*
Second results, changed so only counts presence of word rather than number
All Ages | Mild Mild+ Moderate Moderate+ Adult Adult+: Keto - 46.0 | sexy - 0.002306805074971165
stop
All Ages Mild | Mild+ Moderate Moderate+ Adult Adult+: Keto - 46.0 | Zombie - 0.005376344086021506
stop
All Ages Mild Mild+ | Moderate Moderate+ Adult Adult+: Keto - 46.0 | infected - 0.015873015873015872
stop
All Ages Mild Mild+ Moderate | Moderate+ Adult Adult+: mara - 92.0 | Tubby - 0.024390243902439025
stop
All Ages Mild Mild+ Moderate Moderate+ | Adult Adult+: Cinkoske - 151.0 | BDSM - 0.04040404040404041
stop
All Ages Mild Mild+ Moderate Moderate+ Adult | Adult+: recipes - 509.0 | Emmalin - 0.045454545454545456
*/