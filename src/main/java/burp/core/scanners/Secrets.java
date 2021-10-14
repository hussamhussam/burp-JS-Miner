package burp.core.scanners;

import burp.*;
import burp.utils.InterruptibleCharSequence;
import burp.utils.Utilities;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;

import static burp.utils.Constants.*;
import static burp.utils.Utilities.*;

public class Secrets implements Runnable {
    private static final IBurpExtenderCallbacks callbacks = BurpExtender.getCallbacks();
    private static final IExtensionHelpers helpers = callbacks.getHelpers();
    private final IHttpRequestResponse baseRequestResponse;
    private final UUID taskUUID;

    public Secrets(IHttpRequestResponse baseRequestResponse, UUID taskUUID) {
        this.baseRequestResponse = baseRequestResponse;
        this.taskUUID = taskUUID;
    }

    @Override
    public void run() {
        BurpExtender.getTaskRepository().startTask(taskUUID);
        String responseString = new String(baseRequestResponse.getResponse());
        String responseBodyString = responseString.substring(helpers.analyzeResponse(baseRequestResponse.getResponse()).getBodyOffset());

        Matcher matcherSecrets = SECRETS_REGEX.matcher(new InterruptibleCharSequence(responseBodyString));
        Runnable runnable = () -> {
            // For reporting unique matches with markers
            List<byte[]> uniqueMatchesLow = new ArrayList<>();
            StringBuilder uniqueMatchesSBLow = new StringBuilder();

            List<byte[]> uniqueMatchesHigh = new ArrayList<>();
            StringBuilder uniqueMatchesSBHigh = new StringBuilder();
            while (matcherSecrets.find() && BurpExtender.isLoaded()) {
                double entropy = Utilities.getShannonEntropy(matcherSecrets.group(20)); // group(2) matches our secret
                if (entropy >= 3.5) {
                    // if high entropy, confidence is "Firm"
                    uniqueMatchesHigh.add(matcherSecrets.group().getBytes(StandardCharsets.UTF_8));
                    appendFoundMatches(matcherSecrets.group(), uniqueMatchesSBHigh);
                } else {
                    // if low entropy, confidence is "Tentative"
                    if (isNotFalsePositive(matcherSecrets.group(20))) {
                        uniqueMatchesLow.add(matcherSecrets.group().getBytes(StandardCharsets.UTF_8));
                        appendFoundMatches(matcherSecrets.group(), uniqueMatchesSBLow);
                    }
                }
            }

            reportFinding(baseRequestResponse, uniqueMatchesSBLow, uniqueMatchesLow, uniqueMatchesSBHigh, uniqueMatchesHigh);

            BurpExtender.getTaskRepository().completeTask(taskUUID);
        };
        try {
            Utilities.regexRunnerWithTimeOut(runnable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            BurpExtender.getTaskRepository().timeoutTask(taskUUID);
        }
    }

    private static void reportFinding(IHttpRequestResponse baseRequestResponse, StringBuilder uniqueMatchesSBLow, List<byte[]> uniqueMatchesLow,
                                      StringBuilder uniqueMatchesSBHigh, List<byte[]> uniqueMatchesHigh) {
        if (uniqueMatchesSBHigh.length() > 0) {
            List<int[]> secretsMatchesHigh = getMatches(baseRequestResponse.getResponse(), uniqueMatchesHigh);
            sendNewIssue(baseRequestResponse,
                    "[JS Miner] Secrets / Credentials",
                    "The following secrets (with High entropy) were found in a static file.",
                    uniqueMatchesSBHigh.toString(),
                    secretsMatchesHigh,
                    SEVERITY_MEDIUM,
                    CONFIDENCE_FIRM
            );
        }

        if (uniqueMatchesSBLow.length() > 0) {
            List<int[]> secretsMatchesLow = getMatches(baseRequestResponse.getResponse(), uniqueMatchesLow);
            sendNewIssue(baseRequestResponse,
                    "[JS Miner] Secrets / Credentials",
                    "The following secrets (with Low entropy) were found in a static file.",
                    uniqueMatchesSBLow.toString(),
                    secretsMatchesLow,
                    SEVERITY_MEDIUM,
                    CONFIDENCE_TENTATIVE
            );
        }
    }

    private static boolean isNotFalsePositive(String secret) {
        String[] falsePositives = {"basic", "bearer", "token"};
        // cleanup the secret string
        secret = secret.replaceAll("\\s", "")
                .replace("\t", "")
                .replace("\r", "")
                .replace("\n", "")
                .replace("*", "");
        // at least the secret should equal 4 characters
        if (secret.length() <= 4) {
            return false;
        }

        // Check if secret string is not in the pre-defined blacklist
        for (String fp: falsePositives) {
            if (secret.equalsIgnoreCase(fp)) {
                return false;
            }
        }

        return true;
    }
}