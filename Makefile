SHELL := /bin/zsh

.PHONY: up down restart app flag-old flag-dual flag-new reset-new-db final-scenario verify-seed

up:
	docker compose -f compose.yaml up -d

down:
	docker compose -f compose.yaml down

restart: down up

app:
	./gradlew bootRun

flag-old:
	perl -0pi -e 's/"defaultVariant": "old"|"defaultVariant": "dual"|"defaultVariant": "new"/"defaultVariant": "old"/g' flags.flagd.json

flag-dual:
	perl -0pi -e 's/"defaultVariant": "old"|"defaultVariant": "dual"|"defaultVariant": "new"/"defaultVariant": "dual"/g' flags.flagd.json

flag-new:
	perl -0pi -e 's/"defaultVariant": "old"|"defaultVariant": "dual"|"defaultVariant": "new"/"defaultVariant": "new"/g' flags.flagd.json

reset-new-db:
	docker compose -f compose.yaml exec -T new-db mysql -uapp -papp -D community_new -e "SET FOREIGN_KEY_CHECKS=0; DELETE FROM post_comments; DELETE FROM posts; DELETE FROM boards; DELETE FROM members; SET FOREIGN_KEY_CHECKS=1; ALTER TABLE members AUTO_INCREMENT = 601; ALTER TABLE boards AUTO_INCREMENT = 601; ALTER TABLE posts AUTO_INCREMENT = 1201; ALTER TABLE post_comments AUTO_INCREMENT = 2401;"

verify-seed:
	docker compose -f compose.yaml exec -T old-primary mysql -uapp -papp -D community_old_primary -Nse "select count(*) as members from members; select count(*) as boards from boards; select count(*) as posts from posts; select count(*) as comments from post_comments;"
	docker compose -f compose.yaml exec -T old-secondary mysql -uapp -papp -D community_old_primary -Nse "select count(*) as members from members; select count(*) as boards from boards; select count(*) as posts from posts; select count(*) as comments from post_comments;"
	docker compose -f compose.yaml exec -T new-db mysql -uapp -papp -D community_new -Nse "select count(*) as members from members; select count(*) as boards from boards; select count(*) as posts from posts; select count(*) as comments from post_comments;"

final-scenario:
	./scripts/run_final_scenario.sh
