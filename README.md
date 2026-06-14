# wesite

A Spring Boot 3 platform for domain research and webmaster tooling — 20+ online tools including WHOIS lookup, DNS analysis, SSL inspection, domain valuation, and bulk search, plus a blog and admin backend.

## Features

**Domain tools**
- WHOIS lookup / WHOIS compare / RDAP lookup
- Domain history / Reverse IP / Related domains
- Domain availability / Bulk search / Expiring domains
- Domain valuation / Domain score / Competitor analysis

**Network tools**
- DNS analyzer / Ping test / Port checker
- SSL certificate checker / Email validator / IP geolocation

**Developer tools**
- JSON / XML / HTML formatters
- Timezone converter

**Other**
- Blog system
- AI content generation (DeepSeek integration)
- SEO baked in (auto sitemap, structured data, OG images)

## Tech stack

- **Runtime**: Java 17, Maven
- **Backend**: Spring Boot 3.5, MyBatis-Plus 3.5, Thymeleaf
- **Data**: MySQL 8, Redis, HikariCP
- **Libraries**: MaxMind GeoIP2, DeepSeek API, JWT, dnsjava, jsoup
- **API docs**: SpringDoc OpenAPI

## Project layout

```
wesite-parent/
├── wesite-core/      # Shared module: entities, utilities, common services
├── wesite-web/       # Public site (port 80/8080)
├── wesite-admin/     # Admin backend (port 8082)
├── doc/              # SQL scripts and design documents
└── pom.xml
```

## Getting started

### Prerequisites
- JDK 17+
- Maven 3.8+
- MySQL 8.0+
- Redis 6+
- MaxMind GeoLite2 databases ([download here](https://www.maxmind.com/) — free signup required)

### 1. Initialize the database

```bash
mysql -u root -p -e "CREATE DATABASE wesitedb DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p wesitedb < doc/create.sql
mysql -u root -p wesitedb < doc/insert.sql
mysql -u root -p wesitedb < doc/data_tld_content.sql
mysql -u root -p wesitedb < doc/data_blog_posts.sql
```

### 2. Configure

Copy the templates and fill in your local values (these files are gitignored, so they will not be committed):

```bash
cp wesite-web/src/main/resources/application-prod.properties.example \
   wesite-web/src/main/resources/application-prod.properties
cp wesite-admin/src/main/resources/application-prod.properties.example \
   wesite-admin/src/main/resources/application-prod.properties
```

You can also override settings via environment variables without touching the config files:

| Variable | Description |
| --- | --- |
| `WD_DB_URL` | MySQL JDBC URL |
| `WD_DB_USERNAME` | Database username |
| `WD_DB_PASSWORD` | Database password |
| `REDIS_PASSWORD` | Redis password (may be empty) |
| `JWT_SECRET` | JWT signing secret |
| `DEEPSEEK_API_KEY` | DeepSeek API key (required for AI features) |

### 3. Build and run

```bash
# Build everything
mvn clean install -DskipTests

# Start the public site
cd wesite-web
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Start the admin backend (in another terminal)
cd wesite-admin
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

URLs:
- Public site: http://localhost:80
- Admin backend: http://localhost:8082
- API docs: http://localhost:80/swagger-ui.html

### Production deployment

```bash
mvn clean package -DskipTests -P prod
java -jar wesite-web/target/wesite-web-1.0.0.jar --spring.profiles.active=prod
```

## Contributing

Issues and PRs are welcome. Before submitting:
- Make sure the project builds locally (`mvn clean install`)
- Follow the existing code style
- Never commit configuration files containing real credentials

## License

[MIT](LICENSE) © 2026 Yuanzhi Xu
