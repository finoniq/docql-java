// The README quickstart, kept byte-identical with the README's fenced block
// by scripts/check-quickstart.sh. Set DOCQL_API_KEY, point DOCQL_API_URL at
// the API origin you were given, put an invoice.pdf in the working directory,
// and run it with the JDK source launcher:
//
//   java -cp docql-0.1.0.jar:jackson-databind-2.21.7.jar:jackson-core-2.21.7.jar:jackson-annotations-2.21.jar Quickstart.java

import java.nio.file.Path;

import com.finoniq.docql.DocFile;
import com.finoniq.docql.DocQL;
import com.finoniq.docql.DocQLConnectionException;
import com.finoniq.docql.DocQLException;
import com.finoniq.docql.Instruction;
import com.finoniq.docql.QueryResult;

public class Quickstart {

    public static void main(String[] args) {
        DocQL client = DocQL.builder().build(); // reads DOCQL_API_KEY and DOCQL_API_URL
        try {
            QueryResult res = client.queryDocument(
                    DocFile.path(Path.of("invoice.pdf")),
                    Instruction.query("{ invoice_number total_amount }"));
            System.out.println(res.data());
            System.out.println("request id: " + res.requestId());
            if (res.pageCount() != null) {
                System.out.println("pages: " + res.pageCount());
            }
        } catch (DocQLException e) {
            System.err.println("status " + e.status()
                    + (e.reason() != null ? " reason " + e.reason() : "")
                    + (e.requestId() != null ? " request id " + e.requestId() : ""));
            System.exit(1);
        } catch (DocQLConnectionException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
