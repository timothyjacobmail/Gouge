# Chrome whitelist

An active Chrome whitelist entry permits that domain and its subdomains, or the saved path prefix. `gov.in` is supported explicitly, so approving it covers government agencies such as `traces.tdscpc.gov.in`. Lookalike domains such as `gov.in.example.com` do not match. Other broad public suffixes such as `co.in` remain invalid entries.

The first matching Allow domain rule also grants Chrome whitelist access. Existing approved rules take effect without another website password or wait. For example, Allow suffix `gov.in` covers TRACES, Allow suffix `org` covers `.org` websites, and an Exact Allow rule covers only the named host. An earlier Block rule wins; an earlier Allow rule wins over a later Block rule. Extra site locks, keywords, curfew, and app locks still apply.

Chrome cannot express hostname-contains rules in its managed URL patterns. For a matching Contains Allow rule, Install Lock's accessibility service adds only the verified current hostname to the Chrome policy. A first blocked load may need a refresh after the approval toast. These hostnames are kept only in memory and are rechecked against current rule order on every policy refresh; removing or reordering a rule revokes the resulting access. No universal load-time wildcard is added.

Approved websites can load embedded Google reCAPTCHA, including the Enterprise widget used by Protean's PAN reprint page. Chrome receives HTTPS exceptions limited to `/recaptcha/` on the exact hosts `www.google.com`, `recaptcha.google.com`, `www.recaptcha.net`, and `www.gstatic.com`. These exceptions do not approve destinations in Install Lock's address-bar checks. Extra site locks still override them. When there are no approved websites, no CAPTCHA exceptions are added.

Website additions and domain Allow rules keep their normal task passwords and approval waits. Websites without a matching Allow rule still need a separate website approval.

Live browser validation requires Chrome to be unlocked through the existing app controls. On the connected phone, Chrome was suspended and the browser accessibility service was off, so the live Protean widget could not be checked.

Validation on 2026-11-02: all 298 JVM tests and 14 Chrome policy tests on the connected Vivo phone passed. The device check confirmed that saved domain Allow rules reached Android's actual Chrome restrictions without changing saved website approvals. The app and instrumentation APKs built and the updated app was installed.
