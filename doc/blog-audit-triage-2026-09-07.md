# 线上博客审计结果与逐篇处理计划（2026-09-07）

来源：用户提供的 report.json；SHA-256：`5192260adc411f51dcd99545fb0b6073e9b1ce51a1a207eb080938f62780c91c`。本文件未修改线上数据。

## 核实结果

- 扫描48篇，列出46篇问题文章，46篇均为已发布状态。其余2篇未在findings中，报告无法提供它们的ID、状态或正文。
- 自动重复/相似匹配为0；这只说明没有达到当前文本规则阈值，不能证明不存在重复选题。
- 问题次数可重叠，不应相加作为文章总数。

| 问题 | 篇数 |
| --- | ---: |
| 未检测到合规外部来源 | 43 |
| 缺限制/排查小节 | 36 |
| 缺规则可识别的实例 | 8 |
| 正文不足200词 | 1 |
| 缺小节结构 | 1 |

“缺来源”表示规则未检测到合规外部HTTPS链接，并不等于已证明文章没有任何参考依据。“缺限制/实例”也可能与HTML标记有关，需要看正文。不要批量添加空小节或不支持结论的链接来刷过规则。

## 处置顺序

1. 优先核对57词的EDNS Client Subnet文章，确认是否生成或保存不完整；若确实残缺再撤回修复。该优先级是完整性判断，不是已发现安全漏洞。
2. 对RDAP/WHOIS、DNSSEC两组高优先级重叠候选比较正文，再决定合并或明确分工。
3. 第一批共10篇（包括以上5篇及基础查询、邮件认证文章），形成完整修改稿并验证后分批发布。
4. 第二批完善剩余基础短文及其他长文的证据、适用限制和实例。

## 选题关系：人工候选，不是自动查重结果

| 组 | 文章ID | 当前判断 | 下一步 |
| --- | --- | --- | --- |
| RDAP/WHOIS | 2d309ae665e647b9；56aa534f373449c5 | 标题回答的问题高度重叠 | 比较全文与流量，确定是否合并；暂不指定保留URL |
| DNSSEC | 47a4051d54d34d3c；fe5289e706fe43d1 | 标题及价值承诺高度接近 | 区分原理解释与签名实操，缺乏实质差异时合并 |
| 邮件认证与投递 | 16d4adce62884400；blog007 | 主题交叉，可保留不同任务定位 | 配置指南与故障排查分工，比较全文后决定 |
| 过期检查与生命周期 | blog001；4b48ec2609eb42a4 | 相关但未必重复 | 查询操作与生命周期解释互链，不直接归档 |

## 第一批10篇

| 顺序 | ID | 文章 | 正文词数 | 本轮动作 |
| ---: | --- | --- | ---: | --- |
| 1 | 9ca91492f7204620 | [EDNS Client Subnet Explained: How It Improves CDN Performance and DNS Accuracy](https://whose.domains/blog/edns-client-subnet-explained-how-it-improves-cdn-performance-and-dns-accuracy) | 57 | 优先打开原文检查是否生成、保存或渲染不完整；审计仅57词。若确认是不完整成稿，先撤回草稿，补写原理、实例、限制及来源后发布。 |
| 2 | 2d309ae665e647b9 | [RDAP vs WHOIS: Key Differences and Why RDAP Is the Future of Domain Data Lookup](https://whose.domains/blog/rdap-vs-whois-key-differences-and-why-rdap-is-the-future-of-domain-data-lookup) | 1053 | 与56aa534f373449c5逐段比较。若都回答同一个RDAP/WHOIS对比问题，合并独有信息；保留URL需结合流量、外链和正文决定。 |
| 3 | 56aa534f373449c5 | [RDAP vs WHOIS: Key Differences and Why It Matters for Domain Lookups](https://whose.domains/blog/rdap-vs-whois-key-differences-and-why-it-matters-for-domain-lookups) | 923 | 与2d309ae665e647b9共同审查，当前不能指定此篇归档。核实时间相关表述，不以篇幅决定保留版本。 |
| 4 | 47a4051d54d34d3c | [DNSSEC Explained: How It Protects Your Domain from DNS Spoofing and Cache Poisoning](https://whose.domains/blog/dnssec-explained-how-it-protects-your-domain-from-dns-spoofing-and-cache-poisoning) | 859 | 与fe5289e706fe43d1逐段比较；明确是否能分别定位为原理解释和签名部署实操，否则合并。 |
| 5 | fe5289e706fe43d1 | [DNSSEC Signing Explained: How to Protect Your Domain from DNS Spoofing and Cache Poisoning](https://whose.domains/blog/dnssec-signing-explained-how-to-protect-your-domain-from-dns-spoofing-and-cache-poisoning) | 904 | 与47a4051d54d34d3c共同审查；标题接近不代表正文一致，不直接归档。 |
| 6 | blog003 | [How to Find Domain Owner Information (Even with WHOIS Privacy)](https://whose.domains/blog/how-to-find-domain-owner-information) | 309 | 优先核对标题是否过度承诺获取受隐私保护的所有者信息；明确可查字段、限制和合法联系渠道，补真实查询示例。 |
| 7 | blog001 | [How to Check If a Domain Is Expired in 2026](https://whose.domains/blog/how-to-check-if-a-domain-is-expired) | 339 | 完善具体查询步骤与日期字段解释；与域名生命周期长文分工为“实际检查”和“状态解释”，避免重复扩写。 |
| 8 | 16d4adce62884400 | [SPF, DKIM, and DMARC Explained: How to Set Up Email Authentication to Avoid Spam and Spoofing](https://whose.domains/blog/spf-dkim-and-dmarc-explained-how-to-set-up-email-authentication-to-avoid-spam-and-spoofing) | 1139 | 与blog007比较；建议此篇聚焦认证配置流程，另一篇聚焦投递问题诊断，前提是正文确有不同内容。 |
| 9 | blog007 | [How to Check Email Deliverability: SPF, DKIM, DMARC Explained](https://whose.domains/blog/how-to-check-email-deliverability) | 265 | 265词且主题与认证长文交叉；改成独立排查流程并互链，若无新增诊断价值再考虑合并。 |
| 10 | blog004 | [What Is DNS? A, AAAA, MX, TXT, CNAME Records Explained](https://whose.domains/blog/what-is-dns-records-explained) | 288 | 补一组可执行查询及输出解释，作为基础入口；补来源与适用限制，保留原URL。 |

## 完整46篇处理清单

以下标题相关判断仅作为编辑复核重点，尚未验证为事实错误。未列为第一批的文章仍保留原URL，先核对正文后修改。

| ID | 批次 | 文章 | 正文词数 | 自动检查问题 | 处理建议 |
| --- | --- | --- | ---: | --- | --- |
| 076a448168a74be6 | 后续批次 | [How to Use GeoDNS and Server Load Balancing to Improve Website Performance and Availability](https://whose.domains/blog/how-to-use-geodns-and-server-load-balancing-to-improve-website-performance-and-availability) | 1298 | 未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 1118f873bcd14137 | 后续批次 | [DNS Amplification Attacks Explained: How to Prevent Your DNS Servers from Being Weaponized for DDoS](https://whose.domains/blog/dns-amplification-attacks-explained-how-to-prevent-your-dns-servers-from-being-weaponized-for-ddos) | 971 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 14f033f2016b497d | 后续批次 | [Reverse WHOIS Lookup: How to Discover All Domains Registered by the Same Owner](https://whose.domains/blog/reverse-whois-lookup-how-to-discover-all-domains-registered-by-the-same-owner) | 1114 | 未检测到合规外部来源 | 核对标题中“all domains”的覆盖范围承诺；补数据来源、覆盖限制、隐私边界和示例。 |
| 16d4adce62884400 | 第一批 | [SPF, DKIM, and DMARC Explained: How to Set Up Email Authentication to Avoid Spam and Spoofing](https://whose.domains/blog/spf-dkim-and-dmarc-explained-how-to-set-up-email-authentication-to-avoid-spam-and-spoofing) | 1139 | 缺限制/排查小节；未检测到合规外部来源 | 与blog007比较；建议此篇聚焦认证配置流程，另一篇聚焦投递问题诊断，前提是正文确有不同内容。 |
| 1a9dc117624e4a4e | 后续批次 | [How to Use SSL/TLS Certificate Transparency Logs to Detect Phishing Domains](https://whose.domains/blog/how-to-use-ssltls-certificate-transparency-logs-to-detect-phishing-domains) | 882 | 未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 2009488246d146c8 | 后续批次 | [How to Protect Your Domain from Hijacking: Prevention Tips and Recovery Steps](https://whose.domains/blog/how-to-protect-your-domain-from-hijacking-prevention-tips-and-recovery-steps) | 1130 | 缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 2d309ae665e647b9 | 第一批 | [RDAP vs WHOIS: Key Differences and Why RDAP Is the Future of Domain Data Lookup](https://whose.domains/blog/rdap-vs-whois-key-differences-and-why-rdap-is-the-future-of-domain-data-lookup) | 1053 | 缺限制/排查小节；未检测到合规外部来源 | 与56aa534f373449c5逐段比较。若都回答同一个RDAP/WHOIS对比问题，合并独有信息；保留URL需结合流量、外链和正文决定。 |
| 320ecb74ee6f4b2d | 后续批次 | [CAA Records Explained: How to Restrict Which Certificate Authorities Can Issue SSL/TLS Certificates for Your Domain](https://whose.domains/blog/caa-records-explained-how-to-restrict-which-certificate-authorities-can-issue-ssltls-certificates-for-your-domain) | 962 | 未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 3d15cb66434d49e2 | 后续批次 | [Domain Name Flipping: A Beginner’s Guide to Buying and Selling Domains for Profit](https://whose.domains/blog/domain-name-flipping-a-beginners-guide-to-buying-and-selling-domains-for-profit) | 1034 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 47a4051d54d34d3c | 第一批 | [DNSSEC Explained: How It Protects Your Domain from DNS Spoofing and Cache Poisoning](https://whose.domains/blog/dnssec-explained-how-it-protects-your-domain-from-dns-spoofing-and-cache-poisoning) | 859 | 未检测到合规外部来源 | 与fe5289e706fe43d1逐段比较；明确是否能分别定位为原理解释和签名部署实操，否则合并。 |
| 4b48ec2609eb42a4 | 后续批次 | [Domain Expiration and Redemption Periods: A Complete Guide to What Happens When a Domain Expires](https://whose.domains/blog/domain-expiration-and-redemption-periods-a-complete-guide-to-what-happens-when-a-domain-expires) | 989 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 4d7a06b4a54a490c | 后续批次 | [How to Detect and Prevent Domain Typo Squatting: Tools and Techniques](https://whose.domains/blog/how-to-detect-and-prevent-domain-typo-squatting-tools-and-techniques) | 1202 | 缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 5307fce9d90944b3 | 后续批次 | [DNS SVCB and HTTPS Records Explained: How to Advertise HTTP/3 and Improve Connection Performance](https://whose.domains/blog/dns-svcb-and-https-records-explained-how-to-advertise-http3-and-improve-connection-performance) | 1481 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 56aa534f373449c5 | 第一批 | [RDAP vs WHOIS: Key Differences and Why It Matters for Domain Lookups](https://whose.domains/blog/rdap-vs-whois-key-differences-and-why-it-matters-for-domain-lookups) | 923 | 缺限制/排查小节；未检测到合规外部来源 | 与2d309ae665e647b9共同审查，当前不能指定此篇归档。核实时间相关表述，不以篇幅决定保留版本。 |
| 5b4b91a448274006 | 后续批次 | [How to Use DNS Over HTTPS (DoH) to Improve Privacy and Security](https://whose.domains/blog/how-to-use-dns-over-https-doh-to-improve-privacy-and-security) | 976 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 63f65a2f61ac4cfd | 后续批次 | [DNS CNAME Flattening Explained: How to Use Apex Domains with CDNs and Cloud Services](https://whose.domains/blog/dns-cname-flattening-explained-how-to-use-apex-domains-with-cdns-and-cloud-services) | 434 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 6b9a4bbee11f4f88 | 后续批次 | [Passive DNS Explained: How to Investigate Domain History and Detect Malicious Infrastructure](https://whose.domains/blog/passive-dns-explained-how-to-investigate-domain-history-and-detect-malicious-infrastructure) | 1168 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 6f132135c3bb4d53 | 后续批次 | [How to File a UDRP Complaint to Recover a Cybersquatted Domain](https://whose.domains/blog/how-to-file-a-udrp-complaint-to-recover-a-cybersquatted-domain) | 1270 | 缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 先核对程序、适用条件、费用和时效的一手依据；补实例与限制，不把结果表述为保证。 |
| 7f2f2d926fd9480f | 后续批次 | [Domain Status Codes Explained: What clientTransferProhibited, serverHold, and Other EPP Codes Mean for Your Domain](https://whose.domains/blog/domain-status-codes-explained-what-clienttransferprohibited-serverhold-and-other-epp-codes-mean-for-your-domain) | 1264 | 未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 7f409c189def4719 | 后续批次 | [How to Implement BIMI (Brand Indicators for Message Identification) to Boost Email Trust and Brand Visibility](https://whose.domains/blog/how-to-implement-bimi-brand-indicators-for-message-identification-to-boost-email-trust-and-brand-visibility) | 959 | 缺限制/排查小节 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 8101887bde04437e | 后续批次 | [What Are Glue Records in DNS and When Do You Need Them?](https://whose.domains/blog/what-are-glue-records-in-dns-and-when-do-you-need-them) | 1047 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 9b48fafec6894d56 | 后续批次 | [DNS TTL Optimization: How to Choose the Right Time-to-Live Values for Performance and Reliability](https://whose.domains/blog/dns-ttl-optimization-how-to-choose-the-right-time-to-live-values-for-performance-and-reliability) | 1087 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| 9ca91492f7204620 | 第一批 | [EDNS Client Subnet Explained: How It Improves CDN Performance and DNS Accuracy](https://whose.domains/blog/edns-client-subnet-explained-how-it-improves-cdn-performance-and-dns-accuracy) | 57 | 正文不足200词；缺小节结构；缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 优先打开原文检查是否生成、保存或渲染不完整；审计仅57词。若确认是不完整成稿，先撤回草稿，补写原理、实例、限制及来源后发布。 |
| a9fe3fd9131e4314 | 后续批次 | [DNS Response Policy Zones (RPZ): How to Use Them to Block Malware and Phishing Domains](https://whose.domains/blog/dns-response-policy-zones-rpz-how-to-use-them-to-block-malware-and-phishing-domains) | 1048 | 缺限制/排查小节 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| aefaf10f2db5411c | 后续批次 | [How to Use Reverse DNS (PTR Records) to Improve Email Deliverability and Network Security](https://whose.domains/blog/how-to-use-reverse-dns-ptr-records-to-improve-email-deliverability-and-network-security) | 1031 | 未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| b8d370ec7c1a4992 | 后续批次 | [DNS Anycast Explained: How It Enhances Domain Resolution Speed and Reliability](https://whose.domains/blog/dns-anycast-explained-how-it-enhances-domain-resolution-speed-and-reliability) | 1003 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| bf10e99413f04553 | 后续批次 | [DNS Zone Transfer Attacks: What They Are and How to Protect Your Nameserver Configuration](https://whose.domains/blog/dns-zone-transfer-attacks-what-they-are-and-how-to-protect-your-nameserver-configuration) | 1027 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| blog001 | 第一批 | [How to Check If a Domain Is Expired in 2026](https://whose.domains/blog/how-to-check-if-a-domain-is-expired) | 339 | 缺限制/排查小节；未检测到合规外部来源 | 完善具体查询步骤与日期字段解释；与域名生命周期长文分工为“实际检查”和“状态解释”，避免重复扩写。 |
| blog002 | 后续批次 | [Best Free WHOIS Lookup Tools in 2026](https://whose.domains/blog/best-free-whois-lookup-tools) | 312 | 缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 排名型文章需补可复核的比较维度、实际核对日期和具体体验；无法支撑“Best”时调整标题为选择指南。 |
| blog003 | 第一批 | [How to Find Domain Owner Information (Even with WHOIS Privacy)](https://whose.domains/blog/how-to-find-domain-owner-information) | 309 | 缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 优先核对标题是否过度承诺获取受隐私保护的所有者信息；明确可查字段、限制和合法联系渠道，补真实查询示例。 |
| blog004 | 第一批 | [What Is DNS? A, AAAA, MX, TXT, CNAME Records Explained](https://whose.domains/blog/what-is-dns-records-explained) | 288 | 缺限制/排查小节；未检测到合规外部来源 | 补一组可执行查询及输出解释，作为基础入口；补来源与适用限制，保留原URL。 |
| blog005 | 后续批次 | [How to Transfer a Domain Name Without Downtime (2026 Guide)](https://whose.domains/blog/how-to-transfer-a-domain-name) | 310 | 缺限制/排查小节；未检测到合规外部来源 | 补转移步骤、前置条件和失败回退说明；核对标题中的不间断承诺及年份是否有正文依据。 |
| blog006 | 后续批次 | [Domain Name Valuation Guide: How Much Is Your Domain Worth?](https://whose.domains/blog/domain-name-valuation-guide) | 291 | 缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 补估值方法、样例及不确定性，避免把估值或收益写成保证。 |
| blog007 | 第一批 | [How to Check Email Deliverability: SPF, DKIM, DMARC Explained](https://whose.domains/blog/how-to-check-email-deliverability) | 265 | 缺限制/排查小节；未检测到合规外部来源 | 265词且主题与认证长文交叉；改成独立排查流程并互链，若无新增诊断价值再考虑合并。 |
| blog008 | 后续批次 | [SSL Certificate Explained: What It Is, Why It Matters, How to Check It](https://whose.domains/blog/ssl-certificate-explained-for-non-developers) | 345 | 缺规则可识别的实例；缺限制/排查小节；未检测到合规外部来源 | 补证书查看步骤和示例，解释适用边界并引用直接来源。 |
| c382d2ba3f8e4347 | 后续批次 | [Reverse IP Lookup: How to Find All Domains Hosted on the Same Server](https://whose.domains/blog/reverse-ip-lookup-how-to-find-all-domains-hosted-on-the-same-server) | 1015 | 未检测到合规外部来源 | 核对标题中“all domains”的覆盖范围承诺；补方法局限及可复核示例。 |
| c8dadbe0fad24ff0 | 后续批次 | [Domain Backordering 101: How to Catch Expiring Domains Before Anyone Else](https://whose.domains/blog/domain-backordering-101-how-to-catch-expiring-domains-before-anyone-else) | 908 | 未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| d32a4ba48ff24b6f | 后续批次 | [How to Check if Your Domain Is Blacklisted and How to Fix It](https://whose.domains/blog/how-to-check-if-your-domain-is-blacklisted-and-how-to-fix-it) | 996 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| de8f30f9bc7d477e | 后续批次 | [How to Perform a Domain Security Audit in 2026: Step-by-Step Guide](https://whose.domains/blog/how-to-perform-a-domain-security-audit-in-2026-step-by-step-guide) | 981 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| e254815959514793 | 后续批次 | [How to Use DANE (DNS-Based Authentication of Named Entities) to Secure Email and TLS Without Relying on Certificate Authorities](https://whose.domains/blog/how-to-use-dane-dns-based-authentication-of-named-entities-to-secure-email-and-tls-without-relying-on-certificate-authorities) | 1113 | 未检测到合规外部来源 | 核对标题中不依赖证书颁发机构的适用条件，正文应说明具体部署前提。 |
| e2b286735b294561 | 后续批次 | [NSEC and NSEC3 Records in DNSSEC: How They Prevent Zone Walking and Authenticate Denial of Existence](https://whose.domains/blog/nsec-and-nsec3-records-in-dnssec-how-they-prevent-zone-walking-and-authenticate-denial-of-existence) | 1038 | 缺限制/排查小节；未检测到合规外部来源 | 单独核对标题中防止zone walking的概括是否过强，区分两种机制及其适用限制。 |
| e2b688bb8f64469c | 后续批次 | [MTA-STS and TLS-RPT: How to Enforce Secure Email Delivery with DNS Records](https://whose.domains/blog/mta-sts-and-tls-rpt-how-to-enforce-secure-email-delivery-with-dns-records) | 1061 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| e5510ddbc288433a | 后续批次 | [Understanding SSL Certificate Revocation: CRLs, OCSP, and How to Verify in 2026](https://whose.domains/blog/understanding-ssl-certificate-revocation-crls-ocsp-and-how-to-verify-in-2026) | 1057 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| e781e1e5001a4bbb | 后续批次 | [DNS Propagation Explained: What It Is, Why It Takes Time, and How to Verify It](https://whose.domains/blog/dns-propagation-explained-what-it-is-why-it-takes-time-and-how-to-verify-it) | 1244 | 缺限制/排查小节；未检测到合规外部来源 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| f00f8389d865499b | 后续批次 | [How to Use SSHFP Records in DNS to Verify SSH Host Keys and Prevent Man-in-the-Middle Attacks](https://whose.domains/blog/how-to-use-sshfp-records-in-dns-to-verify-ssh-host-keys-and-prevent-man-in-the-middle-attacks) | 1060 | 缺限制/排查小节 | 保留原URL；逐项核实已有内容及HTML结构，补直接支持结论的来源和缺失要素，再检查事实与示例。 |
| fe5289e706fe43d1 | 第一批 | [DNSSEC Signing Explained: How to Protect Your Domain from DNS Spoofing and Cache Poisoning](https://whose.domains/blog/dnssec-signing-explained-how-to-protect-your-domain-from-dns-spoofing-and-cache-poisoning) | 904 | 缺限制/排查小节；未检测到合规外部来源 | 与47a4051d54d34d3c共同审查；标题接近不代表正文一致，不直接归档。 |

## 修改与发布的控制点

- 当前报告不含作者或AI来源字段，无法据此确认线上署名整改是否完成，需另行查看文章页面。
- 需要正文和流量/外链数据才能决定重复组的保留URL。没有流量数据时标记未知，不以字数或ID猜测优劣。
- 归档前记录独有内容；如旧URL有价值，先配置301。归档不会自动合并正文或设置跳转。
- 46篇未通过机械规则不代表应全部下架。已发布文章保存时要一次补齐阻断项；需多次保存未完成稿时先撤回为草稿。
- 每批发布后重跑审计，对比同一ID的问题和扫描数量，同时核对归档记录、URL、页面及链接。

## 下一步需要的材料

第一批正文可通过配套只读SQL `blog-first-batch-export.sql` 导出；它只读取10篇文章，不归档、不修改数据。使用MySQL客户端的批处理/原始输出模式且关闭列名，结果为一行一个JSON对象（JSONL）。也可直接从Admin逐篇复制正文。需要的流量或外链数据可另附，没有也可先做内容复核。

本次尝试读取4篇公开候选页面未成功，因此尚未完成全文比较；本计划依据审计报告和标题，不把候选关系当作确认重复。
