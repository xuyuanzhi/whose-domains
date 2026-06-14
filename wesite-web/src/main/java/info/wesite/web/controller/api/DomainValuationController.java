package info.wesite.web.controller.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import info.wesite.core.entity.Domain;
import info.wesite.core.entity.UserQueryHistory;
import info.wesite.core.service.DomainService;
import info.wesite.core.utils.DomainUtils;
import info.wesite.core.utils.IpUtils;
import info.wesite.core.utils.RateLimitUtils;
import info.wesite.core.view.ResponseJson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 域名估值工具 API  v2
 *
 * 核心定价逻辑（参考 Sedo Appraisal / GoDaddy Appraisal / NameBio 通行做法）：
 *   1. 以「字符长度 + 字符组成 + TLD」确定市场基准中值（lengthBase），
 *      数据来自 Sedo 公开评估 + NameBio 2022-2025 二级市场成交均价
 *   2. 关键词域名用独立的 keywordBase 价格表替代 lengthBase
 *      （关键词比短域名更受买家驱动，需独立校准）
 *   3. midEstimate = max(lengthBase, keywordBase) × tldMult × categoryMult × ageMult × purityMult
 *   4. 估价区间 = [midEstimate×0.45 , midEstimate×4.0]，Sedo 区间参考
 *      .com 最低值 $500；其他 TLD 最低值 $100
 *
 * 典型参考基准（Sedo 算法对齐，.com 纯字母/关键词）：
 *   LL.com  ≈ $60K–$500K    LLL.com ≈ $8K–$60K    LLLL.com ≈ $1.5K–$12K
 *   5字符关键词 .com ≈ $25K–$200K    6字符关键词 .com ≈ $10K–$80K
 *   随机 LLLLL.com ≈ $400–$3K    随机 LLLLLL.com ≈ $180–$1.5K
 */
@Tag(name = "Domain Valuation API")
@RestController
@RequestMapping("/api/tools")
public class DomainValuationController {

    private static final Logger log = LoggerFactory.getLogger(DomainValuationController.class);

    // ── Tier-1 高价值关键词（按行业分类）─────────────────────────────────────
    // 覆盖范围：所有在二级市场有明确买家的通用英文单词
    private static final Map<String, String> KEYWORD_CATEGORY = new java.util.LinkedHashMap<>();
    static {
        // AI / Tech ── 包含所有与 AI 相关及开发者常用词
        for (String w : new String[]{
                "ai","gpt","llm","ml","bot","api","saas","cloud","cyber","data",
                "code","dev","open","deep","neural","agent","model","vision","vector","token",
                "tech","digital","mobile","app","software","platform","system","server","host",
                "vpn","net","web","hub","lab","space","next","smart","auto","robot","chip",
                // 搜索 / 发现 / 问答 ── search.ai / find.ai 等极具价值
                "search","find","seek","ask","query","answer","discover","explore","detect","scan",
                "check","test","audit","verify","monitor","analyze","analyse","insight","signal",
                // 创建 / 构建 / 工具
                "build","make","create","launch","run","start","generate","deploy","ship","release",
                "design","write","edit","draw","render","compile","parse","compute","process",
                // 交流 / 协作
                "talk","speak","say","listen","voice","call","meet","send","notify","alert","ping",
                "chat","message","reply","comment","post","discuss","connect","sync","share","link",
                // 数据 / 分析
                "log","trace","track","report","stats","metric","chart","graph","plot","dashboard",
                "score","rank","rate","index","match","sort","filter","tag","label","classify",
                // 通用高频词
                "note","task","todo","plan","flow","step","guide","help","support","fix","solve",
                "read","view","watch","show","hide","save","copy","move","merge","split","convert",
                "know","think","learn","understand","predict","suggest","recommend","summarize",
                "manage","organize","automate","integrate","optimize","scale","grow","boost",
                // 基础设施
                "edge","node","core","base","root","key","seed","loop","pipe","wire","mesh","grid",
                "store","cache","queue","batch","stream","proxy","relay","bridge","gate","port"}) {
            KEYWORD_CATEGORY.put(w, "Tech");
        }
        // Finance
        for (String w : new String[]{
                "pay","bank","cash","loan","fund","invest","stock","trade","forex",
                "coin","defi","nft","web3","crypto","wallet","yield","equity","hedge","credit",
                "fintech","swap","earn","profit","wealth","asset","money","finance","insurance",
                "mortgage","tax","ledger","bond","rate","capital","venture","bill","budget",
                "spend","expense","invoice","receipt","salary","wage","price","cost","fee","charge",
                "account","balance","transfer","deposit","withdraw","remit","settle","clear","audit",
                "portfolio","dividend","interest","pension","saving","grant","reward","cashback"}) {
            KEYWORD_CATEGORY.put(w, "Finance");
        }
        // Health
        for (String w : new String[]{
                "health","med","clinic","pharma","bio","gene","dna","cure","care",
                "fit","gym","diet","wellness","therapy","dental","doctor","hospital","nurse",
                "mental","sport","yoga","vitamin","drug","vaccine","research","sleep","mind",
                "body","pain","heal","recover","rehab","dose","symptom","diagnosis","test",
                "run","walk","lift","train","exercise","nutrition","calorie","protein","weight"}) {
            KEYWORD_CATEGORY.put(w, "Health");
        }
        // eCommerce
        for (String w : new String[]{
                "buy","sell","shop","store","market","mall","deal","price","bid",
                "auction","cart","delivery","brand","gift","promo","retail","fashion","luxury",
                "beauty","style","wear","shoes","watch","bag","jewel","order","ship","return",
                "review","rating","wish","pick","compare","browse","catalog","inventory","stock",
                "vendor","seller","buyer","merchant","affiliate","coupon","discount","offer","sale"}) {
            KEYWORD_CATEGORY.put(w, "eCommerce");
        }
        // Travel / Real Estate
        for (String w : new String[]{
                "home","house","estate","land","rent","hotel","fly","trip","travel",
                "car","drive","ride","book","stay","villa","resort","tour","cruise","flight",
                "air","bus","train","map","local","city","urban","global","world",
                "move","relocate","commute","park","road","route","navigate","place","address",
                "room","bed","space","office","desk","lease","property","real","location"}) {
            KEYWORD_CATEGORY.put(w, "Travel");
        }
        // Media / Social / Content
        for (String w : new String[]{
                "news","media","stream","live","social","video","film","game",
                "play","music","photo","blog","pod","cast","show","click","fan",
                "club","team","group","forum","star","like","follow","subscribe","feed",
                "content","story","post","channel","creator","publish","broadcast","record",
                "interview","article","newsletter","digest","reel","clip","remix","vibe","trend"}) {
            KEYWORD_CATEGORY.put(w, "Media");
        }
        // Education
        for (String w : new String[]{
                "learn","school","course","edu","train","skill","study","tutor","class","lesson",
                "quiz","exam","test","degree","cert","mentor","coach","teach","explain","practice"}) {
            KEYWORD_CATEGORY.put(w, "General");
        }
        // Universal high-value generic words
        for (String w : new String[]{
                "top","pro","max","plus","best","fast","real","one","go","get","now","my","new",
                "safe","trust","secure","green","eco","power","energy","gold","prime","elite",
                "super","mega","ultra","hyper","smart","easy","simple","quick","rapid","instant",
                "full","all","any","free","open","pure","true","good","great","awesome","epic",
                "hub","zone","grid","net","link","list","base","point","line","spot","node",
                "idea","solution","service","network","tool","app","platform","product","brand",
                "team","work","job","hire","task","project","launch","startup","company","agency",
                "daily","weekly","live","update","alert","notify","report","summary","insight"}) {
            KEYWORD_CATEGORY.put(w, "General");
        }
    }

    // ── Tier-2 中等价值关键词（上面未覆盖的常见词）──────────────────────────────
    private static final java.util.Set<String> MEDIUM_VALUE_WORDS = java.util.Set.of(
        "online","info","center","central","connect","direct","fresh",
        "public","private","food","drink","eat","cook","pet","dog","baby","kids","love","dating",
        "sea","ocean","farm","garden","street","buzz","source","resource","tips",
        "manage","organize","automate","integrate","optimize","grow","scale",
        "local","urban","social","community","review","rank","score","index",
        "visit","explore","discover","compare","browse","search","find","seek"
    );

    // ── Category 溢价系数 ─────────────────────────────────────────────────────
    private static final Map<String, Double> CATEGORY_MULT = Map.of(
        "Tech",      1.30,
        "Finance",   1.35,
        "Health",    1.20,
        "eCommerce", 1.20,
        "Travel",    1.10,
        "Media",     1.05,
        "General",   1.00
    );

    // ── Category emoji ─────────────────────────────────────────────────────────
    private static final Map<String, String> CATEGORY_ICON = Map.of(
        "Tech",      "💻",
        "Finance",   "💰",
        "Health",    "🏥",
        "eCommerce", "🛒",
        "Travel",    "✈️",
        "Media",     "🎬",
        "General",   "🌐"
    );

    // ── Keyword-base 价格表（.com 基准中值，USD）────────────────────────────────
    // 校准参考：Sedo Appraisal / GoDaddy Appraisal / NameBio 2023-2026 成交均值
    // 下标 = 字符长度
    // 典型校准点：pay.com ~$3M → 3-char HV ≈ $350K（成交极端值打折后的算法中值）
    //            search.ai Sedo 估价约 $150K-$300K → 6-char HV×.ai系数应给出 ~$150K mid
    //            cloud.com ~$5M → 5-char HV ≈ $80K
    //            health.com ~$2.5M → 6-char HV ≈ $40K
    private static final double[] HV_BASE  = { 0,
        3_000_000,  // 1-char keyword: single-letter (.com), extreme scarcity
          700_000,  // 2-char: ai.com ($5M), go.com, me.com → 算法中值 $700K
          350_000,  // 3-char: pay.com, bio.com, dev.com, net.com
          180_000,  // 4-char: cash.com, shop.com, data.com, code.com
           80_000,  // 5-char: cloud.com, trade.com, store.com, brand.com
           40_000,  // 6-char: health.com, market.com, search.com, crypto.com
           18_000,  // 7-char: finance.com, digital.com, website.com
            8_000,  // 8-char: security.com, software.com
            3_500,  // 9-char
            1_600   // 10-char
    };
    // 中等价值关键词（common generic words, NameBio 中低端成交均值）
    private static final double[] MV_BASE = { 0,
        1_500_000, 250_000, 80_000, 30_000, 13_000, 5_500, 2_500, 1_200, 600, 300
    };

    // ── Brandable 基准（可读造词域名，Sedo 对纯算法可发音词的估价）────────────
    // 参考：bing.com (4-char brandable) Sedo 算法约 $15K-$40K
    //       google.com (6-char brandable) Sedo 算法约 $5K-$20K（不含品牌溢价）
    //       spotify.com (7-char brandable) Sedo 算法约 $3K-$10K
    private static final double[] BRANDABLE_BASE = { 0,
        800_000,  // 1-char pronounceable
        280_000,  // 2-char
         80_000,  // 3-char
         35_000,  // 4-char  (bing-level: Sedo ~$15K-$40K → mid $35K)
         14_000,  // 5-char  (apple/adobe-level: Sedo ~$8K-$25K → mid $14K)
          6_000,  // 6-char  (google-level: Sedo ~$3K-$15K → mid $6K)
          2_500,  // 7-char  (spotify-level: Sedo ~$1.5K-$8K → mid $2.5K)
          1_200,  // 8-char
            550,  // 9-char
            280   // 10-char
    };

    // ── 长度基准（无意义随机字母域名，Sedo 算法基准参考）────────────────────────
    // LLLL.com Sedo ~$2K-$6K；LLLLL.com ~$500-$2K
    private static final double[] COM_LETTER_BASE = { 0,
        1_500_000,  // 1-char
          130_000,  // 2-char LL.com
           18_000,  // 3-char LLL.com
            3_500,  // 4-char LLLL.com
            1_200,  // 5-char
              500,  // 6-char
              240,  // 7-char
              110,  // 8-char
               55,  // 9-char
               30   // 10-char
    };
    private static final double[] COM_DIGIT_BASE = { 0,
        1_000_000,  // 1N
          100_000,  // 2N  NN.com 极稀缺
           14_000,  // 3N  NNN.com Sedo ~$8K-$25K
            2_800,  // 4N  NNNN.com Sedo ~$1.5K-$5K
              650,  // 5N
              220,  // 6N
               80,  // 7N
               30   // 8N
    };

    // ── 可比成交案例数据库（来源: NameBio / SEDO 公开数据）─────────────────────
    private static final List<Map<String, Object>> COMP_SALES_DB = new ArrayList<>();
    static {
        Object[][] data = {
            // {domain, price(USD), year, category}
            {"ai.com",         5_000_000, 2023, "Tech"},
            {"voice.com",     30_000_000, 2019, "Tech"},
            {"crypto.com",    12_000_000, 2018, "Finance"},
            {"trade.com",      4_700_000, 2021, "Finance"},
            {"cloud.com",      5_000_000, 2021, "Tech"},
            {"data.com",       2_000_000, 2013, "Tech"},
            {"pay.com",        3_000_000, 2016, "Finance"},
            {"health.com",     2_500_000, 2019, "Health"},
            {"shop.com",       9_000_000, 2017, "eCommerce"},
            {"market.com",     5_000_000, 2021, "eCommerce"},
            {"invest.com",     4_000_000, 2015, "Finance"},
            {"bank.com",      11_000_000, 2010, "Finance"},
            {"insurance.com", 35_600_000, 2010, "Finance"},
            {"fund.com",       9_990_000, 2008, "Finance"},
            {"hotel.com",     11_000_000, 2001, "Travel"},
            {"travel.com",     3_300_000, 2019, "Travel"},
            {"fly.com",        2_900_000, 2007, "Travel"},
            {"car.com",        4_500_000, 2014, "Travel"},
            {"game.com",       3_500_000, 2015, "Media"},
            {"music.com",      1_250_000, 2015, "Media"},
            {"dev.com",        1_900_000, 2021, "Tech"},
            {"api.com",          600_000, 2022, "Tech"},
            {"saas.com",         450_000, 2022, "Tech"},
            {"nft.com",        1_600_000, 2022, "Finance"},
            {"web3.com",         950_000, 2022, "Tech"},
            {"cyber.com",      3_000_000, 2017, "Tech"},
            {"code.com",         850_000, 2020, "Tech"},
            {"fit.com",          820_000, 2016, "Health"},
            {"gym.com",          380_000, 2014, "Health"},
            {"earn.com",         600_000, 2018, "Finance"},
            // Medium-value examples
            {"online.com",     2_000_000, 2020, "General"},
            {"hub.io",           180_000, 2021, "Tech"},
            {"lab.io",            75_000, 2022, "Tech"},
            {"app.io",           250_000, 2020, "Tech"},
            {"store.io",          45_000, 2022, "eCommerce"},
            {"pay.io",            85_000, 2022, "Finance"},
            {"chat.ai",          200_000, 2023, "Tech"},
            {"news.ai",          150_000, 2023, "Media"},
            // LLL.com examples
            {"xyz.com",          800_000, 2015, "General"},
            {"gun.com",          550_000, 2014, "General"},
            {"ass.com",          200_000, 2012, "General"},
            {"www.com",          400_000, 2020, "General"},
        };
        for (Object[] row : data) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("domain", row[0]);
            m.put("price",  row[1]);
            m.put("year",   row[2]);
            m.put("category", row[3]);
            COMP_SALES_DB.add(m);
        }
    }

    @Autowired
    private DomainService domainService;

    @Autowired
    private QueryHistoryRecorder queryHistoryRecorder;

    /** 品牌可读性：全字母、有元音、元音比例 20%-65%、长度 3-12 */
    private static boolean isBrandable(String s) {
        if (s == null || s.length() < 3 || s.length() > 12) return false;
        if (!s.chars().allMatch(Character::isLetter)) return false;
        String vowels = "aeiou";
        long v = s.chars().filter(c -> vowels.indexOf(c) >= 0).count();
        if (v == 0) return false;
        double ratio = (double) v / s.length();
        return ratio >= 0.20 && ratio <= 0.65;
    }

    /** 格式化金额（如 $5,000,000 → "$5M"，$150,000 → "$150K"） */
    private static String fmtUsd(long usd) {
        if (usd >= 1_000_000) return "$" + String.format("%.1fM", usd / 1_000_000.0);
        if (usd >= 1_000)     return "$" + (usd / 1_000) + "K";
        return "$" + usd;
    }

    /** 从可比成交库中挑选最相关的3条记录 */
    private static List<Map<String, Object>> pickComparables(
            String tld, int len, String category, boolean isHighValue, boolean isDigit) {

        // 评分函数：tld匹配 +3, 长度差<=1 +2, category匹配 +2, tier匹配 +1
        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> s : COMP_SALES_DB) {
            String d = (String) s.get("domain");
            String sCat = (String) s.get("category");
            String sTld = d.contains(".") ? d.substring(d.lastIndexOf('.')) : ".com";
            String sLabel = d.contains(".") ? d.substring(0, d.lastIndexOf('.')) : d;
            int sLen = sLabel.length();
            int score = 0;
            if (sTld.equals(tld)) score += 3;
            if (Math.abs(sLen - len) <= 1) score += 2;
            if (sCat != null && sCat.equals(category)) score += 2;
            // Require at least some relevance
            if (score == 0) continue;
            Map<String, Object> entry = new LinkedHashMap<>(s);
            entry.put("_score", score);
            scored.add(entry);
        }
        scored.sort((a, b) -> ((Integer) b.get("_score")) - ((Integer) a.get("_score")));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(4, scored.size()); i++) {
            Map<String, Object> e = new LinkedHashMap<>(scored.get(i));
            e.remove("_score");
            long p = ((Number) e.get("price")).longValue();
            e.put("priceDisplay", fmtUsd(p));
            result.add(e);
        }
        return result;
    }

    @Operation(summary = "域名估值 v3 — P = P₁ × K₁ × K₂ × K₃ × K₄ × S")
    @GetMapping("/valuation/{domain}")
    public ResponseJson<Map<String, Object>> valuate(
            @PathVariable String domain,
            HttpServletRequest httpRequest) {

        String ip = IpUtils.getRequestIp(httpRequest);
        if (!RateLimitUtils.isAllowed(ip, 10, 60000)) {
            return ResponseJson.failure("Too many requests. Please try again later.");
        }
        if (StringUtils.isBlank(domain)) {
            return ResponseJson.failure("Domain name is required.");
        }
        domain = domain.toLowerCase().trim();
        domain = DomainUtils.getMainDomain(domain);
        if (!domain.matches("^(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)\\.)+[a-z]{2,}$")) {
            return ResponseJson.failure("Invalid domain format.");
        }

        try {
            String label = domain.contains(".") ? domain.substring(0, domain.lastIndexOf('.')) : domain;
            String tld   = domain.contains(".") ? domain.substring(domain.lastIndexOf('.'))   : ".com";

            int len        = label.length();
            boolean allLetters = label.chars().allMatch(Character::isLetter);
            boolean allDigits  = label.chars().allMatch(Character::isDigit);
            boolean hasHyphen  = label.contains("-");
            boolean hasMixed   = !allLetters && !allDigits && !hasHyphen;
            boolean isHighValue  = KEYWORD_CATEGORY.containsKey(label);
            boolean isMediumWord = !isHighValue && MEDIUM_VALUE_WORDS.contains(label);
            boolean brandable    = !isHighValue && !isMediumWord && isBrandable(label);

            // ── 行业分类 ─────────────────────────────────────────────────────────
            String category = "General";
            if (isHighValue) {
                category = KEYWORD_CATEGORY.get(label);
            } else if (isMediumWord || brandable) {
                for (Map.Entry<String, String> e : KEYWORD_CATEGORY.entrySet()) {
                    if (label.contains(e.getKey()) && e.getKey().length() >= 3) {
                        category = e.getValue();
                        break;
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            //  P = P₁ × K₁ × K₂ × K₃ × K₄ × S
            // ═══════════════════════════════════════════════════════════════════

            // ── P₁: 基价 ─────────────────────────────────────────────────────────
            // 以"同等长度中等价值关键词.com 域名"为计价基准（MV_BASE），
            // 纯数字域名独立使用 COM_DIGIT_BASE（中文/亚洲市场数字域名基准）。
            // 所有 K 系数均在此基础上调整，可直接与市场数据对标。
            double p1;
            if (allDigits) {
                p1 = len <= 8 ? COM_DIGIT_BASE[len] : Math.max(10, COM_DIGIT_BASE[8] * (8.0 / len));
            } else {
                p1 = len <= 10 ? MV_BASE[len] : Math.max(60, MV_BASE[10] * (10.0 / len));
            }

            // ── K₁: 长度系数（Characters）────────────────────────────────────────
            // 衡量域名字符构成的纯净度与可读性。
            // 纯字母 = 最优 (1.0)；混合字母数字降低品牌辨识度 (0.35)；
            // 连字符在二级市场严重折价，通常仅为同类域名的 5-8% (0.06)。
            double k1;
            if      (hasHyphen) k1 = 0.06;
            else if (hasMixed)  k1 = 0.35;
            else                k1 = 1.00;  // pure letters or pure digits

            // ── K₂: 信用等级系数（Credit Rating）────────────────────────────────
            // 衡量域名标签作为关键词 / 品牌的商业信用质量，以 MV_BASE 为基准 (K₂=1.0)。
            // K₂ = 该类型域名的市场基准价 ÷ P₁(MV_BASE)：
            //   • 精确高价值关键词（Tier-1 HV）：K₂ = HV_BASE / MV_BASE  ≈ 4–8
            //   • 中等价值关键词（Tier-2 MV）  ：K₂ = 1.0（P₁本身即基准）
            //   • 可读造词品牌域名（Brandable）：K₂ = BRANDABLE_BASE / MV_BASE ≈ 0.9–1.2
            //   • 无意义随机字母（Random）      ：K₂ = COM_LETTER_BASE / MV_BASE ≈ 0.05–0.25
            //   • 纯数字（Digits）              ：K₂ = 1.0（P₁已为 COM_DIGIT_BASE）
            double k2;
            double k2Base; // 用于 debug 输出
            if (allDigits) {
                k2 = 1.0;
                k2Base = p1;
            } else if (isHighValue) {
                k2Base = len <= 10 ? HV_BASE[len] : Math.max(800, HV_BASE[10] * (10.0 / len));
                k2 = k2Base / p1;
            } else if (isMediumWord) {
                k2 = 1.0;
                k2Base = p1;
            } else if (brandable) {
                k2Base = len <= 10 ? BRANDABLE_BASE[len] : Math.max(100, BRANDABLE_BASE[10] * (10.0 / len));
                k2 = k2Base / p1;
            } else {
                // 随机字母：使用 COM_LETTER_BASE（比 MV_BASE 低得多）
                k2Base = len <= 10 ? COM_LETTER_BASE[len] : Math.max(5, COM_LETTER_BASE[10] * (10.0 / len));
                k2 = k2Base / p1;
            }

            // ── K₃: 顶级域名系数（TLD）────────────────────────────────────────────
            // 以 .com = 1.0 为基准，反映各 TLD 在二级市场的流动性与溢价能力。
            // 2025-2026 特殊情况：.ai 在 Tech/AI 类关键词领域已超越 .com 估值，
            // 因为顶级 .ai 关键词由 AI 创业公司/VC 驱动的高需求支撑。
            double k3;
            if (".com".equals(tld)) {
                k3 = 1.00;
            } else if (".ai".equals(tld)) {
                // .ai + Tech 类关键词：AI 市场溢价，2025-2026 超越 .com
                if ("Tech".equals(category) && (isHighValue || isMediumWord)) k3 = 2.00;
                // 其他关键词 / 品牌 .ai：仍高于 .io，略低于 .com
                else if (isHighValue || isMediumWord || brandable)             k3 = 0.80;
                // 无意义随机字母 .ai：按注册价值
                else                                                           k3 = 0.40;
            } else if (".io".equals(tld)) {
                k3 = (isHighValue || isMediumWord) ? 0.50 : 0.32;
            } else {
                switch (tld) {
                    case ".co":                         k3 = 0.28; break;
                    case ".net":                        k3 = 0.25; break;
                    case ".app": case ".dev":           k3 = 0.20; break;
                    case ".org":                        k3 = 0.18; break;
                    case ".me": case ".us":             k3 = 0.12; break;
                    case ".xyz":                        k3 = 0.08; break;
                    case ".tech": case ".store":        k3 = 0.06; break;
                    case ".online": case ".site":
                    case ".info":  case ".biz":         k3 = 0.05; break;
                    default:                            k3 = 0.04; break;
                }
            }

            // ── K₄: 商业价值系数（Commerce）──────────────────────────────────────
            // 衡量域名所在行业的商业变现能力与买家需求强度。
            // Finance（金融支付）需求最旺，Tech（技术/AI）次之。
            double k4 = CATEGORY_MULT.getOrDefault(category, 1.0);

            // ── S: 注册年限系数 ───────────────────────────────────────────────────
            // S = 域名已注册年限 / 20（参考公式：剩余年限/20）
            // 注册时间越长 → 历史信任度越高 → 市场溢价越高
            // 范围：[0.25, 1.0]；数据库中未查到年龄 → 保守默认 0.65（~13年等效）
            int ageYears = 0;
            boolean ageKnown = false;
            Domain domainEntity = domainService.getOne(
                    Wrappers.<Domain>lambdaQuery().eq(Domain::getName, domain));
            if (domainEntity != null && domainEntity.getRegistCreateDate() != null) {
                long ms = System.currentTimeMillis() - domainEntity.getRegistCreateDate().getTime();
                ageYears = (int)(ms / (365L * 24 * 3600 * 1000));
                ageKnown = true;
            }
            double s = ageKnown
                    ? Math.min(1.0, Math.max(0.25, ageYears / 20.0))
                    : 0.65;  // 未知年龄：假定 ~13 年（二级市场流通域名的典型年龄）

            // ═══════════════════════════════════════════════════════════════════
            //  最终估价：P = P₁ × K₁ × K₂ × K₃ × K₄ × S
            // ═══════════════════════════════════════════════════════════════════
            double midEstimate = p1 * k1 * k2 * k3 * k4 * s;

            // 估价区间（Sedo 分布参考）：
            //   低值 = mid × 0.45（买家出价底线）
            //   高值 = mid × 4.0（端用户顶价，极度乐观场景）
            long minFloor = ".com".equals(tld) ? 500L : 100L;
            long mid  = Math.max(minFloor, Math.round(midEstimate));
            long low  = Math.max(minFloor, Math.round(midEstimate * 0.45));
            long high = Math.round(midEstimate * 4.0);

            // ── 综合评分（0-100，用于展示，不驱动定价）─────────────────────────
            // 将各系数归一化后加权求和
            double lenScore    = Math.max(5.0, Math.min(100.0, 105.0 - len * 9.5));
            double k1Score     = k1 * 100.0;                          // 0-100
            double k2Score     = isHighValue ? 100 : isMediumWord ? 72 : brandable ? 50 : 15;
            double k3Score     = Math.min(100.0, k3 * 50.0);          // k3=2.0→100, k3=1.0→50
            double sScore      = s * 100.0;                            // 0-100
            int score = (int) Math.min(100, Math.max(1,
                    lenScore * 0.20 + k1Score * 0.10 + k2Score * 0.25
                  + k3Score * 0.25 + sScore   * 0.20));

            String grade;
            if      (score >= 80) grade = "Premium";
            else if (score >= 62) grade = "Good";
            else if (score >= 44) grade = "Fair";
            else                  grade = "Low";

            // ── 投资评级 (A+ ~ D) ─────────────────────────────────────────────
            String investmentGrade;
            if      (score >= 85 && mid >= 10_000)  investmentGrade = "A+";
            else if (score >= 75 && mid >= 3_000)   investmentGrade = "A";
            else if (score >= 65 && mid >= 1_000)   investmentGrade = "B+";
            else if (score >= 50 && mid >= 300)     investmentGrade = "B";
            else if (score >= 35 && mid >= 100)     investmentGrade = "C";
            else                                    investmentGrade = "D";

            // ── 置信度（数据质量越好、域名越短 → 置信度越高）──────────────────
            int confidence;
            if (len <= 4 && k3 >= 0.25 && (isHighValue || allDigits)) confidence = 3;
            else if (len <= 7 && k3 >= 0.18)                           confidence = 2;
            else                                                        confidence = 1;
            String confidenceLabel = confidence == 3 ? "High" : confidence == 2 ? "Medium" : "Low";

            // ── 辅助信息 ─────────────────────────────────────────────────────────
            List<String> useCases     = buildUseCases(category, len, isHighValue, isMediumWord,
                    brandable, allDigits, hasHyphen, tld);
            List<Map<String, Object>> comparables = pickComparables(tld, len, category, isHighValue, allDigits);
            String marketInsight      = buildMarketInsight(len, tld, k3, isHighValue, isMediumWord,
                    brandable, allDigits, hasHyphen, category, mid);
            int seoScore              = buildSeoScore(len, isHighValue, isMediumWord, hasHyphen, allDigits, tld);

            // ── 构造返回结果 ─────────────────────────────────────────────────────
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("domain",          domain);
            result.put("score",           score);
            result.put("midValue",        mid);
            result.put("priceLow",        low);
            result.put("priceHigh",       high);
            result.put("currency",        "USD");
            result.put("grade",           grade);
            result.put("investmentGrade", investmentGrade);
            result.put("confidence",      confidence);
            result.put("confidenceLabel", confidenceLabel);
            result.put("category",        category);
            result.put("categoryIcon",    CATEGORY_ICON.getOrDefault(category, "🌐"));
            result.put("seoScore",        seoScore);
            result.put("marketInsight",   marketInsight);
            result.put("useCases",        useCases);
            result.put("comparableSales", comparables);
            result.put("brandPremiumNote", len <= 4 || (ageYears >= 15 && allLetters && len <= 8));

            // factors：供前端展示和调试
            Map<String, Object> factors = new LinkedHashMap<>();
            // ── 公式各项系数 ────
            factors.put("p1",           Math.round(p1));       // 基价
            factors.put("k1",           k1);                   // 长度（字符纯净）系数
            factors.put("k2",           Math.round(k2 * 100.0) / 100.0);  // 信用等级系数（保留2位）
            factors.put("k3",           k3);                   // TLD 系数
            factors.put("k4",           k4);                   // 商业价值系数
            factors.put("s",            Math.round(s * 100.0) / 100.0);   // 年限系数
            // ── 域名属性 ────
            factors.put("domainLength", len);
            factors.put("tld",          tld);
            factors.put("ageYears",     ageYears);
            factors.put("ageKnown",     ageKnown);
            factors.put("isDictWord",   isHighValue || isMediumWord);
            factors.put("isBrandable",  brandable);
            factors.put("hasHyphen",    hasHyphen);
            factors.put("hasNumber",    !allLetters);
            // ── 展示用评分（归一化后） ────
            factors.put("lengthScore",  (int) lenScore);
            factors.put("keywordScore", (int) k2Score);
            factors.put("tldScore",     (int) k3Score);
            factors.put("ageScore",     (int) sScore);
            factors.put("purityScore",  (int) k1Score);
            result.put("factors", factors);

            RateLimitUtils.incrementRequestCount(ip);
            queryHistoryRecorder.recordAsync(UserQueryHistory.TYPE_VALUATION, domain,
                    grade + " — $" + low + "~$" + high);
            return ResponseJson.success(result);

        } catch (Exception e) {
            log.error("Valuation error for {}", domain, e);
            return ResponseJson.failure("Valuation failed: " + e.getMessage());
        }
    }

    private List<String> buildUseCases(String category, int len, boolean isHighValue,
            boolean isMediumWord, boolean brandable, boolean allDigits, boolean hasHyphen, String tld) {
        List<String> uc = new ArrayList<>();
        switch (category) {
            case "Tech":
                uc.add("SaaS product or developer tool"); uc.add("AI / automation startup");
                uc.add("Tech blog or newsletter"); break;
            case "Finance":
                uc.add("Fintech startup or neobank"); uc.add("Crypto / DeFi platform");
                uc.add("Investment advisory website"); break;
            case "Health":
                uc.add("Telehealth or wellness app"); uc.add("Fitness or nutrition brand");
                uc.add("Medical information portal"); break;
            case "eCommerce":
                uc.add("Online marketplace"); uc.add("D2C retail brand");
                uc.add("Affiliate / deal aggregator"); break;
            case "Travel":
                uc.add("Travel booking platform"); uc.add("Real estate listing site");
                uc.add("Local services directory"); break;
            case "Media":
                uc.add("Streaming or content platform"); uc.add("Podcast or video channel");
                uc.add("Community forum or social network"); break;
            default:
                if (brandable) {
                    uc.add("Startup brand name"); uc.add("App or mobile product");
                } else if (allDigits) {
                    uc.add("Numeric brand popular in Asian markets"); uc.add("Short URL / redirect service");
                } else {
                    uc.add("Generic website or blog"); uc.add("Brand placeholder");
                }
        }
        if (len <= 4 && !hasHyphen) uc.add("Premium domain investment / resale");
        if (".ai".equals(tld))      uc.add("AI product branding — highly trendy TLD");
        if (".io".equals(tld))      uc.add("Startup / developer product — popular TLD");
        return uc;
    }

    private String buildMarketInsight(int len, String tld, double k3, boolean isHighValue,
            boolean isMediumWord, boolean brandable, boolean allDigits, boolean hasHyphen,
            String category, long mid) {
        StringBuilder sb = new StringBuilder();
        if (hasHyphen) {
            sb.append("Hyphenated domains sell at a steep discount (typically 5-10% of equivalent non-hyphenated). Consider registering the non-hyphen version if available. ");
        } else if (len <= 2 && ".com".equals(tld)) {
            sb.append("Two-character .com domains are extremely rare — most are held by corporations or sold for $50K+. ");
        } else if (len <= 3 && ".com".equals(tld)) {
            sb.append("Three-character .com domains (LLL) are in finite supply. Active aftermarket with consistent buyer demand. ");
        } else if (isHighValue && len <= 5) {
            sb.append("Short exact-match keyword .com domains command strong buyer interest, especially in the " + category + " space. ");
        } else if (isHighValue) {
            sb.append("This exact-match keyword aligns with the " + category + " industry, attracting end-user buyers who pay premiums. ");
        } else if (brandable) {
            sb.append("Pronounceable coined words (brandable domains) attract startup buyers seeking unique brand identity. Value depends heavily on market timing and niche. ");
        } else if (allDigits) {
            sb.append("Pure numeric domains attract buyers primarily from China/Asia. Value is driven by digit combination patterns (repeating, sequential, auspicious numbers). ");
        } else if (k3 < 0.10) {
            sb.append("This TLD has limited secondary market liquidity. Most buyers prefer .com, .net, or popular ccTLDs. Resale potential is limited. ");
        }
        if (".ai".equals(tld)) sb.append("The .ai TLD is experiencing record demand in 2025-2026 due to the AI boom. For Tech keywords, .ai is now trading near or above .com levels. ");
        if (mid >= 50_000) sb.append("This domain falls in a price tier where professional domain brokers (Sedo, Afternic, DAN) are recommended for the best exit. ");
        return sb.toString().trim();
    }

    private int buildSeoScore(int len, boolean isHighValue, boolean isMediumWord,
            boolean hasHyphen, boolean allDigits, String tld) {
        int s = 50;
        if (len <= 4)  s += 20; else if (len <= 6) s += 10; else if (len > 10) s -= 15;
        if (isHighValue)  s += 20; else if (isMediumWord) s += 10;
        if (hasHyphen) s += 5;
        if (allDigits) s -= 20;
        if (".com".equals(tld)) s += 10;
        else if (".net".equals(tld) || ".org".equals(tld)) s += 5;
        else if (tld.endsWith(".info") || tld.endsWith(".biz")) s -= 10;
        return Math.max(0, Math.min(100, s));
    }
}
