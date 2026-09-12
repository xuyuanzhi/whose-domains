# Google 索引排查记录（2026-09-12）

## 已核实的事实

- Search Console 全站索引报告更新于 2026-09-04：已收录 1，未收录 125。其中已抓取未收录 119，规范网址不同 1，重定向 5。
- 展开 119 个已抓取未收录的示例，其中 113 个为历史 `/domain/` 查询结果；另外 6 个为 `/tools/whois-lookup`、`/tools`、`/blog`、`/api-docs`、`/top-level-domains`、`/expiring-domains`。不能把这份历史全站报告当成当前 sitemap 的收录结果。
- 唯一“Google 选择的规范网页不同”的示例是 `/domain/datingonlinetr.com`，上次抓取 2026-08-14；该历史查询页也不在当前 sitemap 中。
- 当前 `https://whose.domains/sitemap_all.xml` 包含 118 个 URL：工具及入口 23，帮助文章 40，博客及入口 50，其他 5。没有 `/domain/` 查询结果。
- 提交前，Search Console 显示 sitemap 最后读取于 2026-05-10，发现 77 页。
- 本次已在 Search Console 重新提交同一 sitemap，收到成功确认；最后读取更新为 2026-09-12，发现页数更新为 118。这代表发现成功，不代表 118 页已收录。
- WHOIS 工具 URL 的历史检查：上次抓取为 2026-05-17；抓取成功、允许索引，用户和 Google 的规范网址均为其自身。实时检查显示“网址可编入 Google 索引”。
- WHOIS 工具、`/tools`、`/help-center`、`/blog` 的单页索引请求均已收到“已请求编入索引”回执，进入优先抓取队列。`/tools` 和 `/blog` 历史抓取停留于 2026-08-06；`/help-center` 的历史检查甚至显示“Google 无法识别此网址”，没有抓取记录，不能据此认定当前帮助文章已因内容质量被拒收。
- 正式站执行 `powershell.exe -NoProfile -File scripts/check-seo.ps1` 通过：118 个 URL 均直接返回 HTTP 200、HTML 中存在唯一且匹配的 canonical、没有 robots meta noindex；同时通过 robots sitemap 声明及 www/尾斜杠重定向检查。该脚本没有验证全部 HTTP X-Robots-Tag、Googlebot 专用指令、内容质量或实际索引结果。

## 本地代码修复

`SitemapTask` 过去在查询数据库、生成 XML 前删除旧 sitemap。一旦任务失败，线上文件会缺失。

改为在同一文件系统的临时目录生成，生成成功后原子替换 `sitemap_all.xml`。异常时保留上一版，不删除其他 sitemap；清理本次临时文件。若生成器超出单文件模式，拒绝覆盖上一版并记录错误，需另外实现 sitemap index 后再扩容。

另一个在线复现的问题：`/blog?page=2` 的 canonical 是 `/blog`。修复 `BlogController`，让分页保留 `page` 参数，并保留决定实际内容的分类、标签参数；忽略跟踪参数，合并第一页及重复的分类路由。这样后续分页不再声明自己是第一页的副本。Google 的[分页规范](https://developers.google.com/search/docs/specialty/ecommerce/pagination-and-incremental-page-loading)明确要求每一分页有自己的 canonical。

审核后补充分页边界校验：小于 1 或超过当前结果总页数的请求返回 HTTP 404，在写入列表模型和 canonical 之前终止；分类、标签列表使用各自查询结果的页数。没有文章时保留第 1 页正常展示空状态，第 2 页起返回 404。

新增失败保留、生成期间持续可读、成功替换、不删除独立文件和临时文件清理，以及分页/分类/标签规范网址的回归覆盖。分页边界新增 10 个 HTTP 级用例，修复前 7 个越界用例实际返回 200 而失败，修复后通过。相关测试共 46 项通过。本次代码修复尚未部署，不应将线上重新提交成功归因于本地代码改动。

验证命令：

```powershell
mvn -pl wesite-web -am '-Dtest=SitemapTaskTest,SitemapControllerTest,CanonicalUrlServiceTest,CanonicalRedirectFilterTest,DomainReportIndexPolicyTest,BlogControllerSeoTest,RobotsFileTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
powershell.exe -NoProfile -File scripts/check-seo.ps1
```

## 下一次评估的依据

以本次 sitemap 的 118 个 URL 为集合，检查 Google 是否在最新部署后重新抓取。不要用历史域名结果的排除数作为本次目标的完成标准，也不要为了减少排除数删除有效工具或放开所有查询结果索引。

对重新抓取后仍不收录的页面，逐页评估：是否有可执行的具体用途、真实且注明时间的数据或示例、准确的结果解读与局限、原创分析和来源、从相关工具或文章进入的普通 HTML 内链。补充这些内容应基于实际功能和证据，不批量堆字、改日期或复制通用 FAQ。

Google 没有承诺提交后全部收录；重新抓取可能需要数天至数周。相同 URL 重复提交不会加速抓取。

参考：[请求重新抓取](https://developers.google.com/search/docs/crawling-indexing/ask-google-to-recrawl)、[sitemap 的作用与限制](https://developers.google.com/search/docs/crawling-indexing/sitemaps/overview)、[网页索引报告](https://support.google.com/webmasters/answer/7440203)。
