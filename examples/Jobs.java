// The README jobs example, kept byte-identical with the README's fenced block
// by scripts/check-quickstart.sh. Set DOCQL_API_KEY, point DOCQL_API_URL at
// the API origin you were given, put an invoice.pdf in the working directory,
// and run it with the JDK source launcher and the classpath of the quickstart:
//
//   java -cp <the quickstart's classpath> Jobs.java

import java.nio.file.Path;

import com.finoniq.docql.DocFile;
import com.finoniq.docql.DocQL;
import com.finoniq.docql.DocQLConnectionException;
import com.finoniq.docql.DocQLException;
import com.finoniq.docql.DocQLWaitTimeoutException;
import com.finoniq.docql.Instruction;
import com.finoniq.docql.Job;

public class Jobs {

    public static void main(String[] args) {
        DocQL client = DocQL.builder().build(); // reads DOCQL_API_KEY and DOCQL_API_URL
        try {
            Job job = client.submitDocument(
                    DocFile.path(Path.of("invoice.pdf")),
                    Instruction.query("{ invoice_number total_amount }"));
            job = client.waitFor(job.jobId()); // polls until succeeded or cancelled
            System.out.println(job.status());
            if (job.result() != null) {
                System.out.println(job.result().data());
            }
        } catch (DocQLException e) {
            System.err.println("status " + e.status()
                    + (e.reason() != null ? " reason " + e.reason() : "")
                    + (e.jobId() != null ? " job id " + e.jobId() : "")
                    + (e.requestId() != null ? " request id " + e.requestId() : ""));
            System.exit(1);
        } catch (DocQLWaitTimeoutException e) {
            System.err.println("still running, not cancelled: job id " + e.jobId());
            System.exit(1);
        } catch (DocQLConnectionException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
