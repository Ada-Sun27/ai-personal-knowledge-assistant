.PHONY: demo stop test integration benchmark

demo:
	docker compose up --build
stop:
	docker compose down
test:
	cd backend && mvn -B verify
	cd vector-service && python3 -m pytest -q
	cd web && npm ci && npm test && npm run build
integration:
	python3 scripts/integration_test.py
benchmark:
	cd vector-service && python3 benchmark.py
