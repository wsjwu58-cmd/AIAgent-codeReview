package com.heima.codereview.start.eval;

import com.heima.codereview.rag.model.ReviewRecord;
import com.heima.codereview.rag.vector.MilvusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Profile("eval")
public class RagDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(RagDataSeeder.class);
    private static final String COLLECTION = "code_review_knowledge";

    private final MilvusRepository milvusRepository;
    private final TestDataLoader dataLoader;

    private static final Map<String, String> CHUNK_CONTENTS = Map.ofEntries(
            Map.entry("chunk-001", "数据库密码加密规范：所有数据库密码必须使用 BCrypt 算法进行哈希存储。不得使用 MD5、SHA1 等弱哈希算法。BCrypt 自动包含盐值，可以防止彩虹表攻击。推荐使用 Spring Security 的 BCryptPasswordEncoder 进行密码编码。"),
            Map.entry("chunk-002", "SQL注入防范方法：必须使用参数化查询（PreparedStatement）防止 SQL 注入。禁止使用字符串拼接构建 SQL 语句。对于动态表名/列名，必须使用白名单校验。MyBatis 中应使用 #{} 而非 ${} 传参。"),
            Map.entry("chunk-003", "数据加密标准：敏感数据在存储时应采用 AES-256-GCM 加密。加密密钥必须通过密钥管理服务（如 Vault、KMS）获取，不得硬编码在代码或配置文件中。数据传输必须使用 TLS 1.2+。"),
            Map.entry("chunk-004", "代码审查 checklist：1) 检查 SQL 注入风险 2) 检查 XSS 防护 3) 检查敏感信息是否硬编码 4) 检查异常处理是否安全 5) 检查日志是否包含敏感信息 6) 检查权限校验是否完整 7) 检查依赖版本是否有已知漏洞。"),
            Map.entry("chunk-005", "PreparedStatement 使用规范：所有数据库操作必须使用 PreparedStatement 或 JdbcTemplate。Query 语句中使用 ? 占位符，通过 setString/setInt 等方法设置参数。严禁直接拼接用户输入到 SQL 字符串中。"),
            Map.entry("chunk-006", "日志记录规范：使用 SLF4J 作为日志门面。禁止在日志中输出密码、Token、密钥等敏感信息。使用结构化日志格式：log.info(\"key={}, value={}\", key, value)。生产环境 ERROR 级别应触发告警。"),
            Map.entry("chunk-007", "API接口参数校验：所有 Controller 接口必须使用 @Valid 或 @Validated 进行参数校验。禁止信任客户端传入的任何数据。入参 DTO 应使用 @NotNull、@NotBlank、@Size 等注解约束。字符串参数需做长度限制。"),
            Map.entry("chunk-008", "接口安全设计要求：1) 所有 API 必须经过认证（除登录接口外）2) 敏感操作需二次确认 3) 响应中不得返回未处理的异常信息 4) 接口需做幂等性设计 5) 须有请求频率限制。"),
            Map.entry("chunk-009", "Java 异常处理规范：禁止使用 RuntimeException 替代业务异常。业务异常应继承自定义 BizException。异常信息不应直接返回给前端。全局异常处理器应统一拦截，返回 API 规范格式。异常日志需完整记录堆栈。"),
            Map.entry("chunk-010", "敏感信息存储安全：密钥、Token、密码等敏感信息不得硬编码在代码中。应使用环境变量、application.yml（通过 ${} 引用外部配置）、密钥管理服务（Vault/AWS KMS）进行管理。Git 仓库中不得包含任何密钥信息。"),
            Map.entry("chunk-011", "事务管理规范：Service 层方法上使用 @Transactional 注解声明事务。事务传播行为默认为 REQUIRED。只读操作应设置 readOnly=true 以提升性能。长事务应拆分，避免锁竞争。异常回滚应指定 rollbackFor。"),
            Map.entry("chunk-012", "Redis 缓存使用规范：缓存 Key 命名统一使用 project:module:business:id 格式。必须设置缓存过期时间，禁止永久缓存。缓存数据应能被淘汰而不影响业务。使用 @Cacheable/@CacheEvict 注解简化缓存操作。")
    );

    public RagDataSeeder(MilvusRepository milvusRepository, TestDataLoader dataLoader) {
        this.milvusRepository = milvusRepository;
        this.dataLoader = dataLoader;
    }

    public void seedIfNeeded() {
        List<RagTestQuery> queries = dataLoader.loadTestCasesFromFile(
                "classpath:testdata/rag/rag-queries.json", RagTestQuery.class);
        if (queries.isEmpty()) {
            log.warn("No RAG queries found, skipping data seed");
            return;
        }

        int inserted = 0;
        int skipped = 0;
        String projectId = queries.get(0).projectId();

        for (RagTestQuery query : queries) {
            for (String chunkId : query.relevantChunkIds().keySet()) {
                if (CHUNK_CONTENTS.containsKey(chunkId)) {
                    try {
                        ReviewRecord record = new ReviewRecord(
                                chunkId,
                                "eval-review-" + chunkId,
                                "eval-session",
                                CHUNK_CONTENTS.get(chunkId),
                                projectId,
                                System.currentTimeMillis()
                        );
                        milvusRepository.insert(COLLECTION, record);
                        inserted++;
                        log.info("Seeded chunk {} for project {}", chunkId, projectId);
                    } catch (Exception e) {
                        log.warn("Failed to seed chunk {}: {}", chunkId, e.getMessage());
                        skipped++;
                    }
                }
            }
        }

        log.info("RAG data seeding complete: inserted={}, skipped={}, project={}", inserted, skipped, projectId);
    }
}
