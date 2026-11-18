# Judge

Open **Browser & Google → Judge**, **Chrome whitelist → Judge a website**, or **Chrome → Share → Gouge → Judge for immediate access**. Review the HTTPS address and tap **Judge this website**. A rejection changes no whitelist entry, timer or pending task. The saved approval list remains hidden.

## Why coverage changed

Before 1.10.0-beta.2, a new site could pass only if it was one of three bundled services; local learning mostly repeated completed manual whitelist permissions. That prevented Judge from helping with most new sites. There are now three evidence routes:

- A reviewed service profile: Emirates Driving Company (`edcad.ae`, `www.edcad.ae`, `pay.edcad.ae`, `elearning.edcad.ae`), iLovePDF (`ilovepdf.com`, `www.ilovepdf.com`), Adhoc Safety (`adhocsafety.com`, `www.adhocsafety.com`), or Claude (`claude.ai`). Public content must match that service's identity and purpose. Claude's fixed official `/magic-link` endpoint is a narrowly defined exception: it can approve the exact service host without consuming the one-time credential.
- A completed whole-site manual approval: its exact non-leisure host and conventional `www` counterpart for a two-label domain can qualify with matching identity and practical content. Path-only, pending, local-callback, linked Reddit and broad suffix permissions do not teach blanket trust.
- A new practical site: the visible title/heading must match its registrant's DNS brand (allowing spaces in the brand), and at least 200 characters of visible main/article/body content must provide all three independent signal groups for one purpose. The purposes are document tools, technical documentation, professional services, public services and study resources. Hidden scripts, a vague productive claim, unrelated branding or a few useful keywords cannot establish this evidence. No prior whitelist entry is required.

These are local rules and heuristics, not a general AI classifier or a guarantee about a business. A malicious or changing site can evade content heuristics. Readable practical evidence increases useful coverage; it cannot make web classification foolproof.

## Blocks take priority

Known social, video, anime, manga, gaming, movie and entertainment hosts cannot receive Judge approval, including their subdomains and educational-looking pages. YouTube, Reddit, Instagram, Facebook, X/Twitter, MangaDex, Weeb Central, DeviantArt and Chess.com remain explicit denials. The existing offline manga catalog also vetoes access. Leisure/mixed-purpose content and leisure/proxy/unchecked-redirect routes still fail. Technical data/event streaming is distinguished from entertainment streaming, and an incidental JavaScript help sentence in a substantive page is no longer treated as an anti-bot challenge. The rejection reason reports the matched leisure or personal blocked term where applicable.

Judge reads current completed manual approvals, blocked words, per-word and page exceptions, ordered domain rules, extra locks, Google DNS blocks and manga domains. Exceptions excuse only their applicable personal words; they cannot override built-in leisure denials, extra locks or ordered Block rules. Arbitrary query text cannot manufacture a page's exception context. Broad independent Allow rules retain their existing behavior but do not count as Judge evidence.

## Links, privacy and speed

Embedded Google `/url?q=…` and `/url?url=…` targets are decoded on the phone without contacting Google. Nested embedded wrappers are supported, decoding each target once and preserving encoded paths and sign-in fragments. Conflicting targets are not selected locally. Genuine opaque `share.google`/`search.app` wrappers have a six-second total wall-clock limit; only recognized Google wrapper endpoints are fetched, never the destination just to resolve it. The form no longer waits for transient window focus to present a completed result, and a new share or stopped screen cannot be overwritten by an old result.

Judge has a ten-second total wall-clock budget covering DNS, redirects and response reads. A bounded worker pool cancels expired work instead of queuing checks behind old network operations. Timeouts never approve a site. It follows at most five public HTTPS destinations, rejects credentials/nonstandard ports and private/local IPs, reads at most 512 KiB of HTML, and executes no JavaScript. Cookies, browser credentials and page text are not sent to an AI provider.

Sign-in routes, token-bearing queries and fragments are not fetched for classification. Outside the narrowly reviewed Claude magic-link endpoint, Judge can inspect the public root instead; a failed/unreadable root still gives no approval. An HTTP redirect into an authentication endpoint stops without consuming that link. The original valid link is retained only in memory for the Open button when its exact host passes. The share form's unchanged narrow address carries that original link to Judge; editing the address or choosing a broader scope uses the selection instead. Permanent whitelist choices and persisted Judge grants omit tokens. Tests use synthetic credentials, never a real sign-in link.

## Approval and automatic rechecks

A positive result saves a separate **exact-host HTTPS, port-443 approval**, not a normal whitelist entry. No other host, intermediate wrapper, subdomain, external link, normal delay or ordered rule is approved or changed. Native navigation rejects leisure/unchecked-redirect routes; Chrome receives a matching exact-host managed rule. Other page filters, app locks, curfew, watchdog and device-owner protections continue to apply. Browser monitoring must be connected, including after banking mode.

Each grant is bound to the current settings fingerprint. Content-based grants also retain a provenance flag, so they remain enforceable without a previous manual approval; injecting an arbitrary saved host without current evidence is insufficient. Relevant rule changes or app upgrades deactivate grants until a successful fresh check. An in-flight result made against old rules cannot be saved. Previously granted eligible hosts are automatically rechecked, at most four per batch and at most once per five minutes per failed host. Rechecks fetch public roots without credentials, cannot approve a redirected new host, and cannot resurrect a manually removed grant. No public page contents, credentials or browsing history are persisted for classification.

An independent manual whitelist entry or domain Allow rule can still permit a site when a Judge grant is inactive. Judge does not erase those permissions. Compromised domains, later content changes, third-party resources, manufacturer behavior and disconnected monitoring remain practical limits.

Reviewed public references: [EDC](https://www.edcad.ae/en), [iLovePDF](https://www.ilovepdf.com/), [Adhoc Safety](https://www.adhocsafety.com/) and [Claude's official product page](https://claude.com/product/overview). None of these reviews opened a private authentication link.
