// A stand-in for www.sec.gov and data.sec.gov during end-to-end tests. It serves
// one company (Apple, CIK 320193) with two annual reports so that discovery,
// document processing and period comparison can run without calling SEC.
//
// Usage: node e2e/stub-sec.mjs [port]   (default 4010)
import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const port = Number(process.argv[2] ?? process.env.STUB_SEC_PORT ?? 4010);
const fixtures = join(dirname(fileURLToPath(import.meta.url)), "fixtures");
const read = (name) => readFileSync(join(fixtures, name), "utf8");

const FILINGS = {
  "000032019325000079": { accession: "0000320193-25-000079", document: "aapl-20250927.htm", fixture: "10k-2025.htm" },
  "000032019324000123": { accession: "0000320193-24-000123", document: "aapl-20240928.htm", fixture: "10k-2024.htm" },
};

/** The filing index fixture is for the 2025 report; other filings reuse it with their own names. */
function indexPage(filing) {
  return read("filing-index.htm")
    .replaceAll("000032019325000079", filing.accession.replaceAll("-", ""))
    .replaceAll("0000320193-25-000079", filing.accession)
    .replaceAll("aapl-20250927", filing.document.replace(".htm", ""));
}

function route(path) {
  if (path === "/files/company_tickers_exchange.json") {
    return ["application/json", read("company_tickers_exchange.json")];
  }
  if (path === "/submissions/CIK0000320193.json") {
    return ["application/json", read("CIK0000320193.json")];
  }
  const archive = path.match(/^\/Archives\/edgar\/data\/320193\/(\d{18})\/(.+)$/);
  const filing = archive && FILINGS[archive[1]];
  if (filing && archive[2] === `${filing.accession}-index.htm`) {
    return ["text/html", indexPage(filing)];
  }
  if (filing && archive[2] === filing.document) {
    return ["text/html", read(filing.fixture)];
  }
  return null;
}

createServer((request, response) => {
  const path = new URL(request.url, "http://localhost").pathname;
  const found = request.method === "GET" ? route(path) : null;
  if (!found) {
    response.writeHead(404, { "Content-Type": "text/plain" }).end("Not found");
    return;
  }
  response.writeHead(200, { "Content-Type": `${found[0]}; charset=utf-8` }).end(found[1]);
}).listen(port, () => console.log(`Stub SEC listening on http://localhost:${port}`));
