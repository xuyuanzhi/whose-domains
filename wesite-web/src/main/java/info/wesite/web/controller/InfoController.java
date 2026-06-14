package info.wesite.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.alibaba.fastjson2.JSONObject;

import info.wesite.core.utils.Constants;

@Controller
@RequestMapping("/info")
public class InfoController {

    /**
     * Domain Basics - What is a domain name
     */
    @GetMapping("/what-is-a-domain-name")
    public String whatIsDomainName(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "What is a Domain Name - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn what a domain name is, how it works, and why it's essential for your online presence.");
        model.addAttribute("_pageSchema", buildArticleSchema("What is a Domain Name?",
            "Learn what a domain name is, how it works, and why it's essential for your online presence.",
            "/info/what-is-a-domain-name", "Domain Basics"));
        return "info/what-is-a-domain-name";
    }

    /**
     * Domain Basics - Domain structure and TLDs
     */
    @GetMapping("/domain-structure-and-tlds")
    public String domainStructureAndTlds(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Structure and TLDs - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand domain name structure, top-level domains (TLDs), and how domains are organized.");
        model.addAttribute("_pageSchema", buildArticleSchema("Domain Structure and TLDs",
            "Understand domain name structure, top-level domains (TLDs), and how domains are organized.",
            "/info/domain-structure-and-tlds", "Domain Basics"));
        return "info/domain-structure-and-tlds";
    }

    /**
     * Domain Basics - How domain registration works
     */
    @GetMapping("/how-domain-registration-works")
    public String howDomainRegistrationWorks(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How Domain Registration Works - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn the domain registration process, from choosing a name to activating your website.");
        model.addAttribute("_pageSchema", buildArticleSchema("How Domain Registration Works",
            "Learn the domain registration process, from choosing a name to activating your website.",
            "/info/how-domain-registration-works", "Domain Basics"));
        return "info/how-domain-registration-works";
    }

    /**
     * Domain Basics - Understanding domain ownership
     */
    @GetMapping("/understanding-domain-ownership")
    public String understandingDomainOwnership(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Understanding Domain Ownership - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn about domain ownership rights, responsibilities, and how to verify domain ownership.");
        model.addAttribute("_pageSchema", buildArticleSchema("Understanding Domain Ownership",
            "Learn about domain ownership rights, responsibilities, and how to verify domain ownership.",
            "/info/understanding-domain-ownership", "Domain Basics"));
        return "info/understanding-domain-ownership";
    }

    /**
     * WHOIS Lookup - What is WHOIS
     */
    @GetMapping("/what-is-whois")
    public String whatIsWhois(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "What is WHOIS - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn what WHOIS is, how the WHOIS protocol works, and why it's important for domain research.");
        model.addAttribute("_pageSchema", buildArticleSchema("What is WHOIS?",
            "Learn what WHOIS is, how the WHOIS protocol works, and why it's important for domain research.",
            "/info/what-is-whois", "WHOIS Lookup"));
        return "info/what-is-whois";
    }

    /**
     * WHOIS Lookup - How to perform a WHOIS lookup
     */
    @GetMapping("/how-to-perform-a-whois-lookup")
    public String howToPerformWhoisLookup(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How to Perform a WHOIS Lookup - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Step-by-step guide on performing WHOIS lookups to research domain information.");
        model.addAttribute("_pageSchema", buildArticleSchema("How to Perform a WHOIS Lookup",
            "Step-by-step guide on performing WHOIS lookups to research domain information.",
            "/info/how-to-perform-a-whois-lookup", "WHOIS Lookup"));
        return "info/how-to-perform-a-whois-lookup";
    }

    /**
     * WHOIS Lookup - Reading WHOIS results
     */
    @GetMapping("/reading-whois-results")
    public String readingWhoisResults(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Reading WHOIS Results - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to interpret and understand WHOIS lookup results and domain registration data.");
        model.addAttribute("_pageSchema", buildArticleSchema("Reading WHOIS Results",
            "Learn how to interpret and understand WHOIS lookup results and domain registration data.",
            "/info/reading-whois-results", "WHOIS Lookup"));
        return "info/reading-whois-results";
    }

    /**
     * WHOIS Lookup - Domain privacy protection
     */
    @GetMapping("/domain-privacy-protection")
    public String domainPrivacyProtection(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Privacy Protection - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand domain privacy protection, WHOIS privacy services, and how to protect your personal information.");
        model.addAttribute("_pageSchema", buildArticleSchema("Domain Privacy Protection",
            "Understand domain privacy protection, WHOIS privacy services, and how to protect your personal information.",
            "/info/domain-privacy-protection", "WHOIS Lookup"));
        return "info/domain-privacy-protection";
    }

    /**
     * Domain History - Why domain history matters
     */
    @GetMapping("/why-domain-history-matters")
    public String whyDomainHistoryMatters(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Why Domain History Matters - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn why tracking domain history is important for research, security, and investment decisions.");
        model.addAttribute("_pageSchema", buildArticleSchema("Why Domain History Matters",
            "Learn why tracking domain history is important for research, security, and investment decisions.",
            "/info/why-domain-history-matters", "Domain History"));
        return "info/why-domain-history-matters";
    }

    /**
     * Domain History - Tracking ownership changes
     */
    @GetMapping("/tracking-ownership-changes")
    public String trackingOwnershipChanges(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Tracking Ownership Changes - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to track and monitor domain ownership changes over time.");
        model.addAttribute("_pageSchema", buildArticleSchema("Tracking Ownership Changes",
            "Learn how to track and monitor domain ownership changes over time.",
            "/info/tracking-ownership-changes", "Domain History"));
        return "info/tracking-ownership-changes";
    }

    /**
     * Domain History - DNS record history
     */
    @GetMapping("/dns-record-history")
    public String dnsRecordHistory(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "DNS Record History - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand DNS record history and how to analyze historical DNS configurations.");
        model.addAttribute("_pageSchema", buildArticleSchema("DNS Record History",
            "Understand DNS record history and how to analyze historical DNS configurations.",
            "/info/dns-record-history", "Domain History"));
        return "info/dns-record-history";
    }

    /**
     * Domain History - Expiration and renewal history
     */
    @GetMapping("/expiration-and-renewal-history")
    public String expirationAndRenewalHistory(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Expiration and Renewal History - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn about domain expiration, renewal processes, and tracking renewal history.");
        model.addAttribute("_pageSchema", buildArticleSchema("Expiration and Renewal History",
            "Learn about domain expiration, renewal processes, and tracking renewal history.",
            "/info/expiration-and-renewal-history", "Domain History"));
        return "info/expiration-and-renewal-history";
    }

    /**
     * Domain Security - Domain security basics
     */
    @GetMapping("/domain-security-basics")
    public String domainSecurityBasics(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Security Basics - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn essential domain security practices to protect your domains from threats.");
        model.addAttribute("_pageSchema", buildArticleSchema("Domain Security Basics",
            "Learn essential domain security practices to protect your domains from threats.",
            "/info/domain-security-basics", "Domain Security"));
        return "info/domain-security-basics";
    }

    /**
     * Domain Security - Domain lock and transfer protection
     */
    @GetMapping("/domain-lock-and-transfer-protection")
    public String domainLockAndTransferProtection(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Lock and Transfer Protection - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand domain lock features and how to protect against unauthorized transfers.");
        model.addAttribute("_pageSchema", buildArticleSchema("Domain Lock and Transfer Protection",
            "Understand domain lock features and how to protect against unauthorized transfers.",
            "/info/domain-lock-and-transfer-protection", "Domain Security"));
        return "info/domain-lock-and-transfer-protection";
    }

    /**
     * Domain Security - Understanding DNSSEC
     */
    @GetMapping("/understanding-dnssec")
    public String understandingDnssec(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Understanding DNSSEC - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn about DNSSEC, how it works, and why it's important for domain security.");
        model.addAttribute("_pageSchema", buildArticleSchema("Understanding DNSSEC",
            "Learn about DNSSEC, how it works, and why it's important for domain security.",
            "/info/understanding-dnssec", "Domain Security"));
        return "info/understanding-dnssec";
    }

    /**
     * Domain Security - Preventing domain fraud
     */
    @GetMapping("/preventing-domain-fraud")
    public String preventingDomainFraud(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Preventing Domain Fraud - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to identify and prevent domain fraud, phishing, and other security threats.");
        model.addAttribute("_pageSchema", buildArticleSchema("Preventing Domain Fraud",
            "Learn how to identify and prevent domain fraud, phishing, and other security threats.",
            "/info/preventing-domain-fraud", "Domain Security"));
        return "info/preventing-domain-fraud";
    }

    // ===== SSL/TLS Certificates =====

    @GetMapping("/what-is-ssl-tls-certificate")
    public String whatIsSslTlsCertificate(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "What is an SSL/TLS Certificate - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn what SSL/TLS certificates are, how they work, and why they're essential for website security.");
        model.addAttribute("_pageSchema", buildArticleSchema("What is an SSL/TLS Certificate?",
            "Learn what SSL/TLS certificates are, how they work, and why they're essential for website security.",
            "/info/what-is-ssl-tls-certificate", "SSL/TLS Certificates"));
        return "info/what-is-ssl-tls-certificate";
    }

    @GetMapping("/types-of-ssl-certificates")
    public String typesOfSslCertificates(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Types of SSL Certificates (DV, OV, EV) - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand the different types of SSL certificates: Domain Validation, Organization Validation, and Extended Validation.");
        model.addAttribute("_pageSchema", buildArticleSchema("Types of SSL Certificates",
            "Understand the different types of SSL certificates: Domain Validation, Organization Validation, and Extended Validation.",
            "/info/types-of-ssl-certificates", "SSL/TLS Certificates"));
        return "info/types-of-ssl-certificates";
    }

    @GetMapping("/how-to-check-ssl-certificate")
    public String howToCheckSslCertificate(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How to Check a Domain's SSL Certificate - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn different methods to check and verify a website's SSL/TLS certificate for security and validity.");
        model.addAttribute("_pageSchema", buildArticleSchema("How to Check a Domain's SSL Certificate",
            "Learn different methods to check and verify a website's SSL/TLS certificate for security and validity.",
            "/info/how-to-check-ssl-certificate", "SSL/TLS Certificates"));
        return "info/how-to-check-ssl-certificate";
    }

    @GetMapping("/ssl-certificate-expiration")
    public String sslCertificateExpiration(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "SSL Certificate Expiration & Renewal - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn about SSL certificate lifecycle management, expiration monitoring, and renewal best practices.");
        model.addAttribute("_pageSchema", buildArticleSchema("SSL Certificate Expiration & Renewal",
            "Learn about SSL certificate lifecycle management, expiration monitoring, and renewal best practices.",
            "/info/ssl-certificate-expiration", "SSL/TLS Certificates"));
        return "info/ssl-certificate-expiration";
    }

    // ===== DNS Deep Dive =====

    @GetMapping("/how-dns-works")
    public String howDnsWorks(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How DNS Works: A Complete Guide - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "A complete guide to how the Domain Name System works, including DNS resolution, caching, and security.");
        model.addAttribute("_pageSchema", buildArticleSchema("How DNS Works: A Complete Guide",
            "A complete guide to how the Domain Name System works, including DNS resolution, caching, and security.",
            "/info/how-dns-works", "DNS Deep Dive"));
        return "info/how-dns-works";
    }

    @GetMapping("/common-dns-record-types")
    public String commonDnsRecordTypes(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Common DNS Record Types Explained - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn about A, AAAA, CNAME, MX, TXT, NS, SOA, and other DNS record types and their purposes.");
        model.addAttribute("_pageSchema", buildArticleSchema("Common DNS Record Types Explained",
            "Learn about A, AAAA, CNAME, MX, TXT, NS, SOA, and other DNS record types and their purposes.",
            "/info/common-dns-record-types", "DNS Deep Dive"));
        return "info/common-dns-record-types";
    }

    @GetMapping("/dns-propagation-explained")
    public String dnsPropagationExplained(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "DNS Propagation Explained - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand DNS propagation, why it takes time, and how to speed up DNS changes across the internet.");
        model.addAttribute("_pageSchema", buildArticleSchema("DNS Propagation Explained",
            "Understand DNS propagation, why it takes time, and how to speed up DNS changes across the internet.",
            "/info/dns-propagation-explained", "DNS Deep Dive"));
        return "info/dns-propagation-explained";
    }

    @GetMapping("/choosing-dns-providers")
    public String choosingDnsProviders(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Choosing a DNS Provider - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Compare DNS providers and learn how to choose the right DNS service for performance, security, and features.");
        model.addAttribute("_pageSchema", buildArticleSchema("Choosing a DNS Provider",
            "Compare DNS providers and learn how to choose the right DNS service for performance, security, and features.",
            "/info/choosing-dns-providers", "DNS Deep Dive"));
        return "info/choosing-dns-providers";
    }

    // ===== Advanced Security =====

    @GetMapping("/domain-hijacking-prevention")
    public String domainHijackingPrevention(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How to Prevent Domain Hijacking - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how domain hijacking happens and comprehensive strategies to protect your domains from unauthorized takeover.");
        model.addAttribute("_pageSchema", buildArticleSchema("How to Prevent Domain Hijacking",
            "Learn how domain hijacking happens and comprehensive strategies to protect your domains from unauthorized takeover.",
            "/info/domain-hijacking-prevention", "Advanced Security"));
        return "info/domain-hijacking-prevention";
    }

    @GetMapping("/email-authentication-spf-dkim-dmarc")
    public String emailAuthenticationSpfDkimDmarc(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "SPF, DKIM & DMARC Explained - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand email authentication protocols SPF, DKIM, and DMARC for preventing email spoofing and phishing.");
        model.addAttribute("_pageSchema", buildArticleSchema("SPF, DKIM & DMARC Explained",
            "Understand email authentication protocols SPF, DKIM, and DMARC for preventing email spoofing and phishing.",
            "/info/email-authentication-spf-dkim-dmarc", "Advanced Security"));
        return "info/email-authentication-spf-dkim-dmarc";
    }

    @GetMapping("/phishing-and-domain-spoofing")
    public String phishingAndDomainSpoofing(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Phishing & Domain Spoofing Detection - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to detect and defend against phishing attacks and domain spoofing techniques.");
        model.addAttribute("_pageSchema", buildArticleSchema("Phishing & Domain Spoofing Detection",
            "Learn how to detect and defend against phishing attacks and domain spoofing techniques.",
            "/info/phishing-and-domain-spoofing", "Advanced Security"));
        return "info/phishing-and-domain-spoofing";
    }

    @GetMapping("/typosquatting-protection")
    public String typosquattingProtection(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Typosquatting & Brand Protection - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn about typosquatting threats and strategies to protect your brand from domain name abuse.");
        model.addAttribute("_pageSchema", buildArticleSchema("Typosquatting & Brand Protection",
            "Learn about typosquatting threats and strategies to protect your brand from domain name abuse.",
            "/info/typosquatting-protection", "Advanced Security"));
        return "info/typosquatting-protection";
    }

    // ===== Domain Valuation & Trading =====

    @GetMapping("/how-domain-valuation-works")
    public String howDomainValuationWorks(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How Domain Valuation Works - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Understand the factors that determine domain name value and how to estimate what a domain is worth.");
        model.addAttribute("_pageSchema", buildArticleSchema("How Domain Valuation Works",
            "Understand the factors that determine domain name value and how to estimate what a domain is worth.",
            "/info/how-domain-valuation-works", "Domain Valuation & Trading"));
        return "info/how-domain-valuation-works";
    }

    @GetMapping("/domain-aftermarket-guide")
    public String domainAftermarketGuide(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Aftermarket Guide - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "A comprehensive guide to buying and selling domains on the secondary market.");
        model.addAttribute("_pageSchema", buildArticleSchema("Domain Aftermarket Guide",
            "A comprehensive guide to buying and selling domains on the secondary market.",
            "/info/domain-aftermarket-guide", "Domain Valuation & Trading"));
        return "info/domain-aftermarket-guide";
    }

    @GetMapping("/understanding-domain-auctions")
    public String understandingDomainAuctions(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Understanding Domain Auctions - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how domain auctions work, strategies for buyers and sellers, and the different types of domain auctions.");
        model.addAttribute("_pageSchema", buildArticleSchema("Understanding Domain Auctions",
            "Learn how domain auctions work, strategies for buyers and sellers, and the different types of domain auctions.",
            "/info/understanding-domain-auctions", "Domain Valuation & Trading"));
        return "info/understanding-domain-auctions";
    }

    @GetMapping("/expired-domain-investing")
    public String expiredDomainInvesting(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Expired Domain Investing - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to find and evaluate expired domains for investment, SEO projects, and portfolio building.");
        model.addAttribute("_pageSchema", buildArticleSchema("Expired Domain Investing",
            "Learn how to find and evaluate expired domains for investment, SEO projects, and portfolio building.",
            "/info/expired-domain-investing", "Domain Valuation & Trading"));
        return "info/expired-domain-investing";
    }

    // ===== Domain Management =====

    @GetMapping("/how-to-transfer-a-domain")
    public String howToTransferADomain(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How to Transfer a Domain - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Step-by-step guide to transferring a domain name between registrars safely and without downtime.");
        model.addAttribute("_pageSchema", buildArticleSchema("How to Transfer a Domain",
            "Step-by-step guide to transferring a domain name between registrars safely and without downtime.",
            "/info/how-to-transfer-a-domain", "Domain Management"));
        return "info/how-to-transfer-a-domain";
    }

    @GetMapping("/domain-renewal-best-practices")
    public String domainRenewalBestPractices(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Renewal Best Practices - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Essential best practices for domain renewal to prevent accidental expiration and protect your online assets.");
        model.addAttribute("_pageSchema", buildArticleSchema("Domain Renewal Best Practices",
            "Essential best practices for domain renewal to prevent accidental expiration and protect your online assets.",
            "/info/domain-renewal-best-practices", "Domain Management"));
        return "info/domain-renewal-best-practices";
    }

    @GetMapping("/managing-multiple-domains")
    public String managingMultipleDomains(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Managing Multiple Domains - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Strategies and best practices for managing a portfolio of multiple domain names efficiently.");
        model.addAttribute("_pageSchema", buildArticleSchema("Managing Multiple Domains",
            "Strategies and best practices for managing a portfolio of multiple domain names efficiently.",
            "/info/managing-multiple-domains", "Domain Management"));
        return "info/managing-multiple-domains";
    }

    @GetMapping("/domain-forwarding-and-redirects")
    public String domainForwardingAndRedirects(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Domain Forwarding & Redirects - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn about domain forwarding, redirect types (301, 302), and best practices for URL redirection.");
        model.addAttribute("_pageSchema", buildArticleSchema("Domain Forwarding & Redirects",
            "Learn about domain forwarding, redirect types (301, 302), and best practices for URL redirection.",
            "/info/domain-forwarding-and-redirects", "Domain Management"));
        return "info/domain-forwarding-and-redirects";
    }

    // ===== Domain Research & Tools =====

    @GetMapping("/domain-age-checker-guide")
    public String domainAgeCheckerGuide(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "How to Check Domain Age & Why It Matters - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to check domain age, why it matters for SEO and trust, and how to interpret domain age data.");
        model.addAttribute("_pageSchema", buildArticleSchema("How to Check Domain Age & Why It Matters",
            "Learn how to check domain age, why it matters for SEO and trust, and how to interpret domain age data.",
            "/info/domain-age-checker-guide", "Domain Research & Tools"));
        return "info/domain-age-checker-guide";
    }

    @GetMapping("/reverse-whois-lookup")
    public String reverseWhoisLookup(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Reverse WHOIS Lookup Guide - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to use reverse WHOIS lookups to find domains by owner, email, or organization.");
        model.addAttribute("_pageSchema", buildArticleSchema("Reverse WHOIS Lookup Guide",
            "Learn how to use reverse WHOIS lookups to find domains by owner, email, or organization.",
            "/info/reverse-whois-lookup", "Domain Research & Tools"));
        return "info/reverse-whois-lookup";
    }

    @GetMapping("/ip-to-domain-lookup")
    public String ipToDomainLookup(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "IP to Domain Lookup - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how reverse IP lookups work to find all domains hosted on a specific IP address.");
        model.addAttribute("_pageSchema", buildArticleSchema("IP to Domain Lookup",
            "Learn how reverse IP lookups work to find all domains hosted on a specific IP address.",
            "/info/ip-to-domain-lookup", "Domain Research & Tools"));
        return "info/ip-to-domain-lookup";
    }

    @GetMapping("/bulk-domain-lookup")
    public String bulkDomainLookup(Model model) {
        model.addAttribute(Constants.PAGE_TITLE, "Bulk Domain Lookup Guide - Help Center | Whose.Domains");
        model.addAttribute(Constants.PAGE_META_DESC, "Learn how to research multiple domains at scale using bulk WHOIS lookup tools and techniques.");
        model.addAttribute("_pageSchema", buildArticleSchema("Bulk Domain Lookup Guide",
            "Learn how to research multiple domains at scale using bulk WHOIS lookup tools and techniques.",
            "/info/bulk-domain-lookup", "Domain Research & Tools"));
        return "info/bulk-domain-lookup";
    }

    /**
     * Build Article JSON-LD schema for info/knowledge-base pages (GEO optimization)
     */
    private String buildArticleSchema(String headline, String description, String url, String section) {
        JSONObject schema = new JSONObject();
        schema.put("@context", "https://schema.org");
        schema.put("@type", "Article");
        schema.put("headline", headline);
        schema.put("description", description);
        schema.put("url", "https://whose.domains" + url);
        schema.put("inLanguage", "en-US");
        schema.put("articleSection", section);

        JSONObject author = new JSONObject();
        author.put("@type", "Organization");
        author.put("name", "Whose.Domains");
        author.put("url", "https://whose.domains/");
        schema.put("author", author);
        schema.put("publisher", author);

        JSONObject isPartOf = new JSONObject();
        isPartOf.put("@type", "WebSite");
        isPartOf.put("name", "Whose.Domains Help Center");
        isPartOf.put("url", "https://whose.domains/help-center");
        schema.put("isPartOf", isPartOf);

        // Speakable: AI/voice should read the main content
        JSONObject speakable = new JSONObject();
        speakable.put("@type", "SpeakableSpecification");
        com.alibaba.fastjson2.JSONArray cssSelector = new com.alibaba.fastjson2.JSONArray();
        cssSelector.add("h1");
        cssSelector.add(".info-content");
        speakable.put("cssSelector", cssSelector);
        schema.put("speakable", speakable);

        return schema.toJSONString();
    }

}