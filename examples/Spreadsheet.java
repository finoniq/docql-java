// The README spreadsheet example, kept byte-identical with the README's fenced
// block by scripts/check-quickstart.sh. Set DOCQL_API_KEY, point DOCQL_API_URL
// at the API origin you were given, put a sales.csv in the working directory,
// and run it with the JDK source launcher and the classpath of the quickstart:
//
//   java -cp <the quickstart's classpath> Spreadsheet.java

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.finoniq.docql.DocFile;
import com.finoniq.docql.DocQL;
import com.finoniq.docql.DocQLException;
import com.finoniq.docql.Instruction;
import com.finoniq.docql.QueryOptions;
import com.finoniq.docql.QueryResult;
import com.finoniq.docql.Reasons;

public class Spreadsheet {

    public static void main(String[] args) throws IOException {
        DocQL client = DocQL.builder().build(); // reads DOCQL_API_KEY and DOCQL_API_URL
        byte[] data = Files.readAllBytes(Path.of("sales.csv"));
        try {
            QueryResult res = client.queryDocument(
                    DocFile.bytes(data, ""),
                    Instruction.query("{ region total_sales }"),
                    // a CSV is recognised by its .csv name, sent exactly as given
                    QueryOptions.builder().filename("sales.csv").build());
            if (res.pageCount() != null) {
                System.out.println("pages: " + res.pageCount());
            }
        } catch (DocQLException e) {
            if (!Reasons.SPREADSHEET_TOO_LARGE.equals(e.reason())) {
                throw e;
            }
            JsonNode metadata = e.metadata();
            // pick the axis by name, never by field position
            for (String axis : List.of("cells", "runes", "estimated_output_tokens", "bytes")) {
                if (metadata.has(axis)) {
                    System.out.println("too large: " + axis + "=" + metadata.path(axis).asLong()
                            + " limit=" + metadata.path("limit").asLong());
                    return;
                }
            }
            System.out.println(e.reason()); // no axis this example knows: the reason alone
        }
    }
}
