# Замер производительности на различных concurrency-level.

## Без прокси:

### Без gunicorn (на 127.0.0.1:8000)

* ab -n 2000 -c 1:  Requests per second:    34.79 [#/sec] (mean)
* ab -n 2000 -c 2:  Requests per second:    47.73 [#/sec] (mean)
* ab -n 2000 -c 5:  Requests per second:    67.08 [#/sec] (mean)
* ab -n 2000 -c 10: Requests per second:    63.46 [#/sec] (mean)

### Через gunicorn (4 workers на 127.0.0.1:8000)

* ab -n 2000 -c 1:  Requests per second:    119.40 [#/sec] (mean)
* ab -n 2000 -c 2:  Requests per second:    217.65 [#/sec] (mean)
* ab -n 2000 -c 5:  Requests per second:    410.98 [#/sec] (mean)
* ab -n 2000 -c 10: Requests per second:    403.37 [#/sec] (mean)

## С проксированием: 

### Без gunicorn

* ab -n 2000 -c 1:  Requests per second:    7424.15 [#/sec] (mean)
* ab -n 2000 -c 2:  Requests per second:    23828.53 [#/sec] (mean)
* ab -n 2000 -c 5:  Requests per second:    25576.76 [#/sec] (mean)
* ab -n 2000 -c 10: Requests per second:    23871.76 [#/sec] (mean)

### Через gunicorn (4 workers на 127.0.0.1:8000)

* ab -n 2000 -c 1:  Requests per second:    15730.69 [#/sec] (mean)
* ab -n 2000 -c 2:  Requests per second:    23910.86 [#/sec] (mean)
* ab -n 2000 -c 5:  Requests per second:    24479.50 [#/sec] (mean)
* ab -n 2000 -c 10: Requests per second:    25789.15 [#/sec] (mean)


