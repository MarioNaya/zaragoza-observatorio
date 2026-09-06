# ADR-009 — Memoria acotada de la JVM: el coste del despliegue se paga en RAM residente

- **Fecha**: 2026-09-07
- **Estado**: aceptada
- **Afecta a**: `Dockerfile`, `SPEC.md` §5, `CLAUDE.md` regla 26, ADR-008, `docs/despliegue.md`
- **Se apoya en**: métricas reales de la instancia y medición local con `compose.prod.yaml` (§ Consecuencias); precios de Railway consultados el 2026-09-07

## Contexto

Railway factura **RAM residente a 10 $/GB/mes** y CPU a 20 $/vCPU/mes; **no cobra por petición**, y el egress (0,05 $/GB) es despreciable para una API que sirve JSON pequeño. En una aplicación con poco tráfico, la factura la determina casi por completo la memoria que el proceso mantiene ocupada las 24 horas, no las visitas.

Medido en la instancia recién desplegada, en una hora sin uso real (10 peticiones, egress 0 MB):

| Servicio | Actual | Media | Pico |
|---|---|---|---|
| `observatorio` | 688 MB | 531 MB | 704 MB |
| `postgis` | 80 MB | 125 MB | 208 MB |

Los ~690 MB del servicio de la aplicación son **consecuencia directa de una decisión equivocada de ADR-008**: el `Dockerfile` fijaba `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`, y Railway le presenta al contenedor el límite del plan (8 GB). La JVM entendía que podía usar ~6 GB de heap; con ese margen no tiene ninguna razón para recoger basura con frecuencia ni para devolver páginas al sistema operativo. El proceso no tenía una fuga: tenía permiso.

## Decisión

1. **Tope de memoria explícito, no porcentual.** `-Xms128m -Xmx256m -XX:MaxMetaspaceSize=192m -Xss512k`. Un porcentaje es engañoso en una plataforma que anuncia el límite del plan y no una reserva del servicio.
2. **`-XX:+UseSerialGC`.** Con un heap pequeño y un servicio sin requisitos de latencia, el recolector paralelo/G1 solo aporta estructuras y hilos. Este proceso pasa el día esperando respuestas HTTP.
3. **`-XX:TieredStopAtLevel=1` y `-XX:ReservedCodeCacheSize=64m`.** Solo compilador C1: se renuncia al pico de rendimiento del JIT a cambio de bastante menos memoria de código y de compilación. Es un intercambio favorable aquí porque **el trabajo es de entrada/salida, no de cálculo**: la ingesta espera a la API municipal (con 0,5 s de cortesía entre peticiones) y el muestreo hace 60 peticiones cada 10 minutos. Si algún día aparece un cálculo intensivo —agregaciones territoriales de la fase 4—, esta bandera es lo primero que hay que revisar.
4. **`-XX:+ExitOnOutOfMemoryError`.** Con una sola instancia y reinicio automático, es preferible morir y volver a arrancar que quedar agonizando: las ingestas son idempotentes (regla 5) y las publicaciones de eventos pendientes se reintentan al arrancar (ADR-004).
5. **`server.tomcat.threads.max: 20`** en el perfil `prod`. El tope de 200 está pensado para otra escala.
6. **No se usa el modo Serverless de Railway** (§ Alternativas descartadas).

## Consecuencias

- **De 690 MB a 368 MB**, medido en local con la imagen de producción sobre una ingesta completa (436 fichas, 497 operaciones del Swagger, 369 federados) más un lote de 60 observaciones: memoria estable en 367,8 MB durante siete minutos, sin crecimiento ni `OutOfMemoryError`. Es un **47 % menos**.
- En coste: el servicio pasa de ~6,9 $/mes a ~3,7 $/mes. Con `postgis` (~1,25 $) y el volumen de 5 GB (0,75 $), el proyecto ronda los **5,7 $/mes**.
- El arranque es ligeramente más lento en régimen permanente por el C1, pero irrelevante: el trabajo real lo marca la latencia de la API municipal, no la nuestra.
- Si en el futuro hiciera falta bajar más, la vía es la **imagen nativa con GraalVM** (80–150 MB), a costa de un build más largo y de configurar la reflexión de Hibernate, Flyway, Modulith y springdoc. No se hace ahora: el ahorro (~2 $/mes) no compensa el riesgo de tocar el arranque de una instancia que acaba de empezar a acumular la serie de instantáneas, que es lo único irrepetible del proyecto.

## Alternativas descartadas

- **Modo Serverless (antes App Sleeping)**: duerme el servicio tras 5–10 minutos sin paquetes salientes. **No aplica a esta aplicación, y no por un detalle de configuración sino por su naturaleza**: mantiene el pool de conexiones a `postgis` por la red privada y sale a la API municipal cada 10 minutos (`tick: PT10M`), así que no llegaría a dormirse; y si lo hiciera, el planificador no se ejecutaría y la serie diaria de instantáneas —la única razón de tener esto desplegado— dejaría de acumularse. Este servicio no espera visitas: trabaja solo. Serverless es para lo contrario.
- **Filtrar bots con Cloudflare para reducir la factura**: el rastreo automatizado de vulnerabilidades es real y molesto, pero **aquí no es el problema de coste**: Railway no cobra por petición y el egress medido es 0 MB. Diez mil respuestas 4xx al mes son unos pocos MB, céntimos. Además exige un dominio propio, que este proyecto no tiene. Se reconsiderará si algún día se pone un dominio delante, y entonces por motivos de higiene, no de factura.
- **Bajar el heap por debajo de 256 MB**: lo que queda por encima del heap es sobre todo metaspace y código, no datos; recortar el heap más aún daría poco y arriesgaría la ingesta del catálogo, que maneja documentos de 2 MB.
- **Reducir el volumen de 5 GB**: son 0,75 $/mes y encogerlo no es trivial. Se revisará cuando se sepa cuánto ocupa de verdad la serie histórica.
- **Apagar el servicio entre ejecuciones con un cron externo**: reintroduce el problema del Serverless (el planificador es el producto) y añade una pieza que mantener.

## Referencias

- <https://docs.railway.com/reference/pricing> — RAM 10 $/GB/mes, CPU 20 $/vCPU/mes, egress 0,05 $/GB, volumen 0,15 $/GB/mes; sin coste por petición
- <https://docs.railway.com/reference/app-sleeping> — Serverless: duerme tras ~5–10 min sin paquetes salientes; lo despierta tráfico de internet o de la red privada
