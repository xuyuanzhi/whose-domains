package info.wesite.admin.task;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.DomainSite;
import info.wesite.core.entity.DomainTld;
import info.wesite.core.service.DomainService;
import info.wesite.core.service.DomainSiteService;
import info.wesite.core.service.DomainTldService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.RdapUtils;
import info.wesite.core.utils.WhoisUtils;

@Profile({ "prod" })
@Component
@EnableScheduling
public class SyncDomainTask {

	protected static Logger logger = LoggerFactory.getLogger(SyncDomainTask.class);

	private static Map<String, BlockingQueue<Domain>> whoisMap = new HashMap<>();
	private static Map<String, BlockingQueue<Domain>> rdapMap = new HashMap<>();

	private static ExecutorService executorService = Executors.newCachedThreadPool();

	@Autowired
	private DomainService domainService;
	@Autowired
	private DomainSiteService domainSiteService;
	@Autowired
	private DomainTldService domainTldService;

	/**
	 * 生成www子域名的定时任务
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public void generateSubDomains() {
		LambdaQueryWrapper<Domain> query = Wrappers.<Domain>lambdaQuery().eq(Domain::getStatus, Domain.STATUS_ACTIVE)
				.notLikeRight(Domain::getName, "www.")
				.and(q -> q.like(Domain::getName, "%.%").or().like(Domain::getName, "%.%.%"))
				.notExists("select ID from web_domain_site where DELETED = 0 and DOMAIN_ID = web_domain.ID");

		long total = domainService.count(query);
		if (total == 0) {
			return;
		}

		long limit = 500;
		long totalPage = total % limit == 0 ? total / limit : total / limit + 1;

		logger.info("查询到 {} 条没有子域名的主域名，共 {} 页", total, totalPage);

		for (int i = 1; i <= totalPage; i++) {
			logger.info("第 {} 页开始生成www子域名", i);
			Page<Domain> pageData = domainService.page(Page.of(i, limit), query.orderByAsc(Domain::getName));
			if (pageData.getRecords() != null) {

				for (Domain d : pageData.getRecords()) {
					try {
						String wwwName = "www." + d.getName();
						DomainSite site = DomainUtils.getDomainSiteByName(wwwName);
						if (site != null) {
							site.setDomainId(d.getId());
							site.setMainName(d.getName());
							site.setCreateBy("task");
							site.setCreateTime(new Date());
							domainSiteService.save(site);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}

			logger.info("第 {} 页生成www子域名完成", i);
		}
	}

	/**
	 * 处理whois服务器和rdap服务器都为空的域名
	 */
//	@Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
//	public void start() {
//		logger.info("开始处理whois服务器和rdap服务器都为空的域名========>>>");
//
//		LambdaQueryWrapper<Domain> query = Wrappers.<Domain>lambdaQuery().isNull(Domain::getParentWhoisServer)
//				.isNull(Domain::getWhoisServer).isNull(Domain::getParentRdapServer).isNull(Domain::getRdapServer)
//				.eq(Domain::getStatus, Domain.STATUS_ACTIVE).exists("select * from ", null);
//
//		long total = domainService.count(query);
//		if (total == 0) {
//			return;
//		}
//
//		long limit = 500;
//		long totalPage = total % limit == 0 ? total / limit : total / limit + 1;
//
//		logger.info("查询到 {} 条数据，共 {} 页", total, totalPage);
//
//		for (int i = 1; i <= totalPage; i++) {
//			logger.info("第 {} 页", i);
//			Page<Domain> pageData = domainService.page(Page.of(i, limit), query.orderByAsc(Domain::getCreateTime));
//			if (pageData.getRecords() != null) {
//				logger.info("查询到 {} 条数据", pageData.getRecords().size());
//
//				for (Domain d : pageData.getRecords()) {
//					try {
//						String tld = DomainUtils.getTld(d.getName()).substring(1);
//
//						DomainTld byTld = domainTldService
//								.getOne(Wrappers.<DomainTld>lambdaQuery().eq(DomainTld::getName, tld));
//
//						if (byTld == null) {
//							logger.error("域名【{}】没有查询到顶级域名记录", d.getName());
//							continue;
//						}
//
//						if (StringUtils.isNotBlank(byTld.getRdapServer())) {
//							d.setParentRdapServer(byTld.getRdapServer());
//						} else if (StringUtils.isNotBlank(byTld.getWhoisServer())) {
//							d.setParentWhoisServer(byTld.getWhoisServer());
//						} else {
//							logger.error("顶级域名【{}】没有whois和rdap服务", tld);
//							continue;
//						}
//
//						logger.info("域名【{}】已更新whois/rdap服务器", d.getName());
//						domainService.updateById(d);
//
////						String whoisServer = WhoisUtils.getWhoisServerFromIANA(d.getName());
////						if (StringUtils.isNotBlank(whoisServer)) {
////							if (whoisMap.containsKey(whoisServer)) {
////								whoisMap.get(whoisServer).add(d);
////							} else {
////								ArrayBlockingQueue<Domain> queue = new ArrayBlockingQueue<>(500);
////								queue.add(d);
////								whoisMap.put(whoisServer, queue);
////
////								executorService.execute(new FetchWhoisThread(whoisServer, queue));
////							}
////						} else {// 查询rdap服务器
////							String rdapServer = RdapUtils.getServerByDomain(d.getName());
////							if (StringUtils.isNotBlank(rdapServer)) {
////								if (rdapMap.containsKey(rdapServer)) {
////									rdapMap.get(rdapServer).add(d);
////								} else {
////									ArrayBlockingQueue<Domain> queue = new ArrayBlockingQueue<>(500);
////									queue.add(d);
////									rdapMap.put(rdapServer, queue);
////
////									executorService.execute(new FetchRdapThread(rdapServer, queue));
////								}
////
////							} else {
////								d.setUpdateBy("task");
////								d.setUpdateTime(new Date());
////								domainService.updateById(d);
////								logger.error("域名 {} 无法获取whois/rdap数据，请关注！！！！", d.getName());
////							}
////						}
//					} catch (Exception e) {
//						logger.error("域名【{}】查询失败", d.getName(), e);
//					} finally {
////						try {
////							Thread.sleep(5000);
////						} catch (InterruptedException e) {
////						}
//					}
//				}
//			}
//		}
//
//		logger.info("处理whois服务器和rdap服务器都为空的域名结束<<<==========");
//	}

	/**
	 * 处理whois服务器存在，但没有抓取数据的域名
	 */
	@Scheduled(fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
	public void fetchWhoisText() {
		logger.info("查询whois服务器存在，但是没有whois数据的域名========>>>");

		LambdaQueryWrapper<Domain> query = Wrappers.<Domain>lambdaQuery().isNotNull(Domain::getParentWhoisServer)
				.isNull(Domain::getParentWhoisText).eq(Domain::getStatus, Domain.STATUS_ACTIVE);

		long total = domainService.count(query);
		if (total == 0) {
			return;
		}

		long limit = 500;
		long totalPage = total % limit == 0 ? total / limit : total / limit + 1;

		logger.info("查询到 {} 条缺少whois信息的域名，共 {} 页", total, totalPage);

		for (int i = 1; i <= totalPage; i++) {
			logger.info("第 {} 页", i);
			Page<Domain> pageData = domainService.page(Page.of(i, limit), query.orderByAsc(Domain::getCreateTime));
			if (pageData.getRecords() != null) {
				logger.info("查询到 {} 条数据", pageData.getRecords().size());

				for (Domain d : pageData.getRecords()) {
					try {
						if (whoisMap.containsKey(d.getParentWhoisServer())) {
							BlockingQueue<Domain> queue = whoisMap.get(d.getParentWhoisServer());
							if (queue.size() < limit) {
								queue.add(d);
							} else {
								Thread.sleep(10000);
							}
						} else {
							ArrayBlockingQueue<Domain> queue = new ArrayBlockingQueue<>(500);
							queue.add(d);
							whoisMap.put(d.getParentWhoisServer(), queue);

							executorService.execute(new FetchWhoisThread(d.getParentWhoisServer(), queue));
						}
					} catch (Exception e) {
						logger.error("域名【{}】查询失败", d.getName(), e);
					} finally {
						try {
							Thread.sleep(5000);
						} catch (InterruptedException e) {
						}
					}
				}
			}
		}

		logger.info("查询whois服务器存在，但是没有whois数据的域名结束<<<==========");
	}

	/**
	 * 处理rdap服务器存在，但没有抓取数据的域名
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public void fetchRdapText() {
		logger.info("查询rdap服务器存在，但是没有rdap数据的域名========>>>");

		LambdaQueryWrapper<Domain> query = Wrappers.<Domain>lambdaQuery().isNotNull(Domain::getParentRdapServer)
				.isNull(Domain::getParentRdapText).eq(Domain::getStatus, Domain.STATUS_ACTIVE);

		long total = domainService.count(query);
		if (total == 0) {
			return;
		}

		long limit = 500;
		long totalPage = total % limit == 0 ? total / limit : total / limit + 1;

		logger.info("查询到 {} 条缺少rdap信息的域名，共 {} 页", total, totalPage);

		for (int i = 1; i <= totalPage; i++) {
			logger.info("第 {} 页", i);
			Page<Domain> pageData = domainService.page(Page.of(i, limit), query.orderByAsc(Domain::getCreateTime));
			if (pageData.getRecords() != null) {
				logger.info("查询到 {} 条数据", pageData.getRecords().size());

				for (Domain d : pageData.getRecords()) {
					try {
						if (rdapMap.containsKey(d.getParentRdapServer())) {
							rdapMap.get(d.getParentRdapServer()).add(d);
						} else {
							ArrayBlockingQueue<Domain> queue = new ArrayBlockingQueue<>(500);
							queue.add(d);
							whoisMap.put(d.getParentRdapServer(), queue);

							executorService.execute(new FetchRdapThread(d.getParentRdapServer(), queue));
						}
					} catch (Exception e) {
						logger.error("域名【{}】查询失败", d.getName(), e);
					} finally {
						try {
							Thread.sleep(5000);
						} catch (InterruptedException e) {
						}
					}
				}
			}
		}

		logger.info("查询rdap服务器存在，但是没有rdap数据的域名结束<<<==========");
	}

	private class FetchWhoisThread implements Runnable {

		private String whoisServer;

		private BlockingQueue<Domain> queue;

		public FetchWhoisThread(String server, BlockingQueue<Domain> queue) {
			this.whoisServer = server;
			this.queue = queue;
		}

		@Override
		public void run() {
			while (true) {
				Domain d = queue.poll();
				if (d == null) {
					whoisMap.remove(whoisServer);
					break;
				}

				Domain domain = domainService.getById(d.getId());

				try {
					String text = WhoisUtils.getWhoisText(d.getName(), whoisServer);
					if (StringUtils.isBlank(text)) {
						//
						logger.warn("no text from {}", whoisServer);
					} else if (text.contains("No match for")) {
						//
						logger.warn("error text from {}", whoisServer);
					} else {
						//
						domain.setParentWhoisServer(whoisServer);
						domain.setParentWhoisText(text);

						String nextServer = WhoisUtils.getWhoisServerFromText(text);
						if (StringUtils.isNotBlank(nextServer)) {
							domain.setWhoisServer(nextServer);
						}
						domain.setUpdateBy("task");
						domain.setUpdateTime(new Date());
						domainService.updateById(domain);
					}
				} catch (Exception e) {
					e.printStackTrace();
					try {
						Thread.sleep(90000);
					} catch (InterruptedException e2) {
					}
				} finally {
					try {
						Thread.sleep(30000);
					} catch (InterruptedException e) {
					}
				}
			}
		}

	}

	private class FetchRdapThread implements Runnable {

		private String rdapServer;

		private BlockingQueue<Domain> queue;

		public FetchRdapThread(String server, BlockingQueue<Domain> queue) {
			this.rdapServer = server;
			this.queue = queue;
		}

		@Override
		public void run() {
			while (true) {
				Domain d = queue.poll();
				if (d == null) {
					rdapMap.remove(rdapServer);
					break;
				}

				Domain domain = domainService.getById(d.getId());

				try {
					String text = RdapUtils.getText(d.getName(), rdapServer);
					if (StringUtils.isBlank(text)) {
						//
						logger.warn("no text from {}", rdapServer);
					} else if (text.contains("No match for")) {
						//
						logger.warn("error text from {}", rdapServer);
					} else {
						//
						domain.setParentRdapServer(rdapServer);
						domain.setParentRdapText(text);
						domain.setUpdateBy("task");
						domain.setUpdateTime(new Date());
						domainService.updateById(domain);
					}
				} catch (Exception e) {
					e.printStackTrace();
					try {
						Thread.sleep(60000);
					} catch (InterruptedException e2) {
					}
				} finally {
					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {
					}
				}
			}
		}

	}
}
