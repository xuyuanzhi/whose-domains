# DNS 完整内容唯一约束上线说明

## 约束与代码

`DNS_LIVE_FINGERPRINT` 为数据库维护的 BINARY(32) 虚拟生成列。DELETED=0 时，对 `HEX(DOMAIN_ID):HEX(NAME):HEX(TYPE):HEX(VALUE)` 计算 SHA-256，通过 `UK_DNS_LIVE_FINGERPRINT` 保证唯一。

HEX 中没有冒号，字段边界无歧义；完整 VALUE 参与计算，保留大小写、末尾空格及 Unicode 字节差异。STATUS 不参与，失效记录可恢复有效。DELETED 非 0 或身份字段有 NULL 时指纹为 NULL，不约束，与本次清理范围一致。

DomainDnsRecordWriter 由主域名和子域名刷新共用。查询保留 DOMAIN_ID 索引入口，再逐字节核对身份；命中旧行后使用当前锁定读再次确认，避免旧快照中的已删除记录导致观测丢失。并发插入发生唯一键冲突时，也通过 FOR UPDATE 当前读查找同一条记录，再更新状态、TTL 和时间。找不到相同内容则抛出冲突，不覆盖其他记录；这也防范极罕见的摘要碰撞。

生成列不映射到实体，代码兼容迁移前后的表结构。历史重复记录不会通过 LIMIT 1 任意选择。该修复不调整整个刷新流程的状态替换语义；死锁等其他事务异常仍按任务异常处理。

## 上线顺序

1. 部署兼容代码到 Web、Admin 及其他 DNS 写入实例。代码不依赖新列，可先部署。
2. 暂停全部 DNS 写入入口，包括定时任务、网页触发及手动导入，等待在途事务完成。
3. 按完整字节规则复查存量重复，必要时再次分批去重；之前完成清理不代表以后没有新增重复。
4. 显式选中正确的数据库，使用稳定连接逐条执行 `doc/alter_domain_dns_unique_fingerprint.sql`：添加虚拟列、建立新唯一索引、最后检查并移除旧前缀唯一索引。检查段必须在同一连接连续执行。构建索引要扫描数据并计算摘要，客户端读取超时必须允许 DDL 完成。
5. 检查新索引 Non_unique=0、Visible=YES，并通过 SHOW CREATE TABLE 核对生成表达式；确认旧 IDX_NAME_VALUE_TYPE 不存在。检查其他唯一索引是否仍有不兼容约束。脚本返回 STOP 时不得认定迁移成功。处理完毕后恢复 DNS 写入，观察刷新日志。

## 旧索引与新建库

历史基线存在 `IDX_NAME_VALUE_TYPE(DOMAIN_ID,NAME,VALUE(255),TYPE)`，会误拒绝不同长 TXT 或逻辑删除后重建的记录。

迁移只在新指纹唯一索引已就绪、且旧索引确实为上述四列和 255 前缀的 BTREE 唯一索引时，才移除旧索引。不会先删除旧索引再建立新索引，也不会修改其他名称的索引。

旧索引不存在时正常跳过；同名索引定义异常，或新索引缺失、非唯一时，保留现有索引并返回 STOP 提示。**STOP 是结果集而非 SQL 异常，必须检查并处理。** 对保留的异常索引，人工核实用途后再制定迁移方案。

`doc/create.sql` 已直接采用指纹生成列和唯一索引，不再创建旧前缀唯一索引。使用新基线建库后无需重复执行添加列/索引的升级脚本；升级文件用于旧表。

## 失败与恢复

- 各条 DDL 分别提交，不能整体回滚。添加列成功、创建新索引失败时保留列及旧索引；解决原因后只重试创建新索引的 ALTER，成功后再执行旧索引检查段。
- 1062 表示建索引遇到重复或极罕见的摘要碰撞；先核查完整内容。脚本不删除记录，也不使用 IGNORE 或 REPLACE。
- 新索引已建立、移除旧索引失败时，保留新索引；从 `-- Retire legacy prefix index` 开始重跑完整检查段，不能只重放 EXECUTE。该检查段可重复执行。
- LOCK=NONE / ALGORITHM=INPLACE 不支持时停止，不自动降级到锁表复制。
- lock_wait_timeout=15 仅限制元数据锁等待，不限制索引构建时间。连接中断后先查实际 DDL 状态，不能盲目重跑整个文件。
- 不向生产表插入测试数据；使用隔离测试库验证约束。

## 可重复验证

隔离实例使用 MySQL 8.0.36、127.0.0.1:13370、库名 wesite_dns_unique_test。测试会删除并重建该隔离库中的 WEB_DOMAIN_DNS，严禁指向业务库。

```powershell
mvn -pl wesite-core '-Dtest=DomainDnsRecordWriterTest,DomainDnsUniqueMySqlTest' '-DdnsTestJdbc=jdbc:mysql://127.0.0.1:13370/wesite_dns_unique_test?allowPublicKeyRetrieval=true' test
```

未提供参数时集成测试默认跳过。测试覆盖长 TXT、大小写/空格、逻辑删除、并发旧快照、旧索引迁移、新建库基线、异常同名索引保留，以及检查段重跑。

## 当前状态

本次交付代码、SQL 和测试；线上表结构尚未修改。必须按上述部署、暂停写入和复查步骤后再执行生产迁移。