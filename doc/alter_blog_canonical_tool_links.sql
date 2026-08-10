UPDATE `WEB_BLOG_POST`
SET `CONTENT` = REPLACE(
    REPLACE(
        REPLACE(
            REPLACE(
                `CONTENT`,
                '/tools/domain_analyzer',
                '/tools/domain-analyzer'
            ),
            '/tools/dns_analyzer',
            '/tools/dns-analyzer'
        ),
        '/tools/ssl_checker',
        '/tools/ssl-checker'
    ),
    '/tools/competitor_analysis',
    '/tools/competitor-analysis'
)
WHERE LOCATE('/tools/domain_analyzer', `CONTENT`) > 0
   OR LOCATE('/tools/dns_analyzer', `CONTENT`) > 0
   OR LOCATE('/tools/ssl_checker', `CONTENT`) > 0
   OR LOCATE('/tools/competitor_analysis', `CONTENT`) > 0;
