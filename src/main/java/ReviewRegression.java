import com.ericrobertbrewer.bookspider.sites.db.DatabaseHelper;
import com.ericrobertbrewer.bookspider.sites.meta.BookCave;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Logger;

public class ReviewRegression {

    static String[] keywords = {"Keto", "mara", "Cinkoske", "recipes", "sexy", "Zombie", "infected", "Tubby", "BDSM", "Emmalin" };
    static String[] levels = {"All Ages", "Mild", "Mild+", "Moderate", "Moderate+", "Adult", "Adult+"  };
    static double[] loci = new double[levels.length];
    static double[] thresholds = new double[levels.length - 1];
    static double[] weights;

    static DatabaseHelper db = new DatabaseHelper(Logger.getLogger("string".getClass().getSimpleName()));


    //todo, figure out how to determine which keywords to use, what the loci should be, and how to find the threshold values for an assignment

    public static void main(String args[]){

        //init loci
        for(int i = 0; i < loci.length; i++){
            loci[i] = 2*i;
        }

        //init thresholds
        for(int i = 0; i < thresholds.length; i++){
            thresholds[i] = (loci[i] + loci[i+1])/2;
        }

        //get reviews
        db.connectToContentsDatabase();
        List<String> texts = new LinkedList<>();
        List<String> ratings = new LinkedList<>();
        try {


            List<BookCave.BookRating> books = db.getBookCaveRatings();
            for(BookCave.BookRating book : books){
                try {
                    List<String> lines = Files.readAllLines(Paths.get("Reviews/" + book.bookId +".txt"));
                    ratings.add(book.rating);
                    String text = "";
                    for(String line : lines){
                        //line = line.replaceAll("#", "*");
                        text += line;

                    }
                    texts.add(text);
                }catch (IOException io){
                    //System.out.println(book.bookId);
                }
            }
        }catch (SQLException e){
            System.out.println("BAD SQL");
        }

        //mine for keywords
        /*Set<String> words = new HashSet<>();
        for(String text:texts){
            String[] w = text.split(" ");
            for(int i = 0; i < w.length; i++){
                words.add(w[i]);
            }

        }
        //keywords = new String[words.size()];
        keywords = new String[2];
        int k = 0;
        for(String word: words){
            if(k == keywords.length) break;
            keywords[k] = word;
            k++;

        }*/

        Map<String, Map<String, Integer>> counts = WordCounter.getCounts();
        Set<String> words = new HashSet<>();
        final int NUM_TERMS = 3;
        for(int i = 1; i <= thresholds.length; i++){
            Borderlines b = WordCounter.getMaxRat(i, NUM_TERMS, counts);
            for(int j = 0; j < NUM_TERMS; j++){
                words.add(b.heads[j]);
                words.add(b.feet[j]);
            }
        }
        keywords = new String[words.size()];
        int k = 0;
        for(String word: words) {
            if (k == keywords.length) break;
            keywords[k] = word;
            k++;
        }


        //Note

        //format reviews
        Map<String,Integer> indicer = new HashMap<>();
        for(int i = 0; i < levels.length; i++){
            indicer.put(levels[i], i);
        }
        //train/test split
        double[][] test_vectors = new double[texts.size()/4][];
        int[] test_indices = new int[ratings.size()/4];
        double[][] train_vectors = new double[texts.size() - (texts.size()/4)][];
        int[] train_indices = new int[ratings.size() -(ratings.size()/4)];
        for(int i = 0; i < train_vectors.length; i++){
            train_vectors[i] = vectorize(texts.get(i));
            train_indices[i] = indicer.get(ratings.get(i));
        }
        for(int i = 0; i < test_vectors.length; i++){
            test_vectors[i] = vectorize(texts.get(i + train_vectors.length));
            test_indices[i] = indicer.get(ratings.get(i + train_indices.length));
        }

        learnWeights(train_vectors, train_indices);

        //calculate accuracy
        int correct = 0;
        for(int i = 0; i < test_vectors.length; i++){
            double s = score(test_vectors[i]);
            String c = classify(s);
            if(c.equals(ratings.get(i + train_vectors.length))) correct++;
        }
        System.out.println((1.0 * correct)/test_vectors.length);
        //generate confusion matrix
        int[][] matrix = new int[levels.length][levels.length];
        for(int i = 0; i < test_vectors.length; i++){
            double s = score(test_vectors[i]);
            String c = classify(s);
            matrix[indicer.get(ratings.get(i + train_vectors.length))][indicer.get(c)]++;
        }
        for(int i = 0; i < matrix.length; i++){
            for(int j = 0; j < matrix[i].length; j++){
                System.out.print(matrix[i][j] + ", ");
            }
            System.out.println();
        }


    }


    static double[] vectorize(String text){

        double[] vector = new double[keywords.length +  1];
        vector[0] = 1; //allows constant shift
        for(int i = 0; i < keywords.length; i++){
            /*if(text.contains(keywords[i])){
                vector[i + 1] = 1;
            }*/
            String[] words = text.split(" ");
            for(int j = 0; j < words.length; j++){
                if(words[j].contains(keywords[i])) vector[i + 1]++;
            }
        }



        return vector;

    }

    static double score(double[] vector){
        double s = 0;
        for(int i = 0; i < vector.length; i++){
            s += vector[i] * weights[i];
        }
        return s;
    }


    static String classify(double score){

        for(int i = 0; i < thresholds.length; i++){
            if(score < thresholds[i]){
                return levels[i];
            }
        }
        return  levels[thresholds.length];

    }

    static void learnWeights(double[][] vectors, int[] classIndices){

        //convert indices to scores
        double[] scores = new double[classIndices.length];
        for(int i = 0; i < classIndices.length; i++){
            scores[i] = loci[classIndices[i]];
        }

        //just a MSE linear regression
        MultipleLinearRegression MLR = new MultipleLinearRegression(vectors, scores);
        weights = new double[keywords.length + 1];
        for(int i = 0; i < weights.length; i++){
            weights[i] = MLR.beta(i);
        }






    }

}
