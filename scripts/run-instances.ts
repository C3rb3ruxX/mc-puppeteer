/**
 * run-instances.ts — lanza N instancias de Minecraft (cliente, Fabric) con
 * mc-puppeteer instalado, cada una con su directorio de juego, su puerto HTTP,
 * su config y su copia de Baritone.
 *
 * Uso (funciona igual en Bun y en Node, sin ninguna dependencia):
 *
 *     bun   scripts/run-instances.ts -n 3
 *     node scripts/run-instances.ts -n 3
 *
 * Que hace, en orden:
 *
 *   1. Compila el mod (`./gradlew build`) salvo que se pase `--no-build`.
 *   2. Pide a Gradle como Loom arranca el cliente y lo guarda en
 *      `scripts/.run-config.json` (tarea `dumpRunConfig`, definida en
 *      `scripts/gradle-run-config.init.gradle`).
 *   3. Se descarga Baritone para Minecraft 1.21.5 a `scripts/.cache/` y se
 *      instala en el `mods/` de cada instancia. NO se toca `~/.minecraft`.
 *   4. Calcula un puerto libre por instancia: si 25580 esta ocupado, usa el
 *      siguiente (25581, 25582...). Si la carpeta de la instancia ya existe,
 *      le anade `-2`, `-3`... en vez de fallar.
 *   5. Escribe `config/mc-puppeteer.json` y `options.txt` de cada instancia.
 *   6. Arranca un JVM por instancia con el classpath que dio Gradle y espera a
 *      que cada una responda en `GET /puppeteer/health`.
 *
 * Cada instancia corre en su propio directorio, asi que el juego y el mod no
 * comparten nada: mundo, opciones, config, puerto, token y logs.
 */

import { spawn, type ChildProcess } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { basename, delimiter, dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

// --------------------------------------------------------------------------- tipos

type GlMode = 'auto' | 'software' | 'hardware'

type Options = {
	instances: number
	portBase: number
	host: string
	memory: string
	width: number
	height: number
	dir: string
	prefix: string
	java: string
	username: string | null
	baritone: string
	baritoneVersion: string
	token: string | null
	requireToken: boolean
	clean: boolean
	build: boolean
	restart: boolean
	verbose: boolean
	timeout: number
	gl: GlMode
	refresh: boolean
	wait: boolean
	help: boolean
}

/** Lo que Gradle dice sobre como arrancar el cliente. */
type RunConfig = {
	mainClass: string
	classpath: string[]
	jvmArgs: string[]
	programArgs: string[]
	env: Record<string, string>
	runDir: string
}

/** Cada linea que llega de un hijo. `setEncoding` es opcional por si acaso. */
type OutputStream = {
	setEncoding?: (encoding: string) => void
	on: (event: string, handler: (chunk: string | Buffer) => void) => unknown
}

type Instance = {
	name: string
	dir: string
	host: string
	port: number
	token: string
	requireToken: boolean
	proc: ChildProcess | null
	restarts: number
	ready: boolean
	logFile: string
}

// --------------------------------------------------------------------------- defaults

/** Minecraft 1.21.5: la release de Baritone que declara `minecraft: ["1.21.5"]`. */
const BARITONE_VERSION = '1.14.0'
const baritoneUrl = (version: string): string =>
	`https://github.com/cabaletta/baritone/releases/download/v${version}/baritone-standalone-fabric-${version}.jar`
/**
 * Hash del jar tal y como lo sirve GitHub, no el de `checksums.txt`: en la v1.14.0
 * el checksums.txt se subio 18 s despues del jar y no corresponde al binario que
 * hay colgado (el juego lo carga y arranca Baritone sin problema). Anclar el
 * hash real sirve para detectar que un dia se sirva otro fichero.
 */
const BARITONE_SHA256: Record<string, string> = {
	'1.14.0': '4f46560d306e0e01c7b93494e99ab50b27abadd3b56ba97baef4c9650b6e6bcf',
}

const DEFAULTS: Options = {
	instances: 2,
	portBase: 25580,
	host: '127.0.0.1',
	memory: '2G',
	width: 854,
	height: 480,
	dir: 'run-instances',
	prefix: 'mc',
	java: '',
	username: null,
	baritone: 'auto',
	baritoneVersion: BARITONE_VERSION,
	token: null,
	requireToken: false,
	clean: false,
	build: true,
	restart: false,
	verbose: false,
	timeout: 240,
	gl: 'auto',
	refresh: false,
	wait: true,
	help: false,
}

// --------------------------------------------------------------------------- log

const started = Date.now()
const stamp = (): string => {
	const s = Math.floor((Date.now() - started) / 1000)
	return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}
const say = (msg: string): void => console.log(`${stamp()}  ${msg}`)
const warn = (msg: string): void => console.error(`${stamp()}  AVISO  ${msg}`)
const fail = (msg: string): never => {
	console.error(`${stamp()}  ERROR  ${msg}`)
	process.exit(1)
}

// --------------------------------------------------------------------------- fs

const ensureDir = (path: string): string => {
	mkdirSync(path, { recursive: true })
	return path
}

const readJson = <T>(path: string): T | null => {
	try {
		return JSON.parse(readFileSync(path, 'utf8')) as T
	} catch {
		return null
	}
}

/** Escribe por temporal + rename: si el script muere a medias no queda un config corrupto. */
const writeJsonAtomic = (path: string, data: unknown): void => {
	ensureDir(dirname(path))
	const tmp = `${path}.tmp`
	writeFileSync(tmp, `${JSON.stringify(data, null, '\t')}\n`, 'utf8')
	renameSync(tmp, path)
}

/** Borra recursivo. `rmSync` con `recursive` ya resuelve el arbol entero. */
const rmrf = (path: string): void => {
	if (existsSync(path)) rmSync(path, { recursive: true, force: true })
}

const sha256 = (path: string): string => createHash('sha256').update(readFileSync(path)).digest('hex')

// --------------------------------------------------------------------------- puertos y carpetas

/**
 * Primer puerto libre a partir de `from`: si 25580 esta ocupado devuelve 25581,
 * y asi sucesivamente. Nunca falla por un puerto ocupado.
 */
const pickPort = async (from: number, host: string, taken: Set<number>): Promise<number> => {
	const free = (port: number): Promise<boolean> =>
		new Promise(done => {
			const probe = createServer()
			probe.once('error', () => done(false))
			probe.listen(port, host, () => probe.close(() => done(true)))
		})
	for (let port = from; port <= 65535; port++) {
		if (taken.has(port)) continue
		if (await free(port)) {
			taken.add(port)
			return port
		}
	}
	throw new Error(`No hay puertos libres a partir de ${from}`)
}

/** Carpeta libre para la instancia: si existe, `-2`, `-3`... en vez de reventar. */
const uniqueDir = (root: string, name: string, clean: boolean): string => {
	const first = join(root, name)
	if (clean) {
		rmrf(first)
		return first
	}
	if (!existsSync(first)) return first
	for (let n = 2; n < 1000; n++) {
		const candidate = join(root, `${name}-${n}`)
		if (!existsSync(candidate)) return candidate
	}
	throw new Error(`Demasiadas instancias llamadas ${name}`)
}

// --------------------------------------------------------------------------- args

const parseArgs = (argv: string[]): Options => {
	const opts: Options = { ...DEFAULTS }
	const need = (i: number, flag: string): string => {
		const value = argv[i + 1]
		if (value === undefined) fail(`${flag} necesita un valor`)
		return value
	}

	for (let i = 0; i < argv.length; i++) {
		const arg = argv[i]
		switch (arg) {
			case '-n':
			case '--instances':
				opts.instances = Number(need(i, arg))
				i++
				break
			case '-p':
			case '--port-base':
				opts.portBase = Number(need(i, arg))
				i++
				break
			case '--host':
				opts.host = need(i, arg)
				i++
				break
			case '-m':
			case '--memory':
				opts.memory = need(i, arg)
				i++
				break
			case '-w':
			case '--width':
				opts.width = Number(need(i, arg))
				i++
				break
			case '--height':
				opts.height = Number(need(i, arg))
				i++
				break
			case '--dir':
				opts.dir = need(i, arg)
				i++
				break
			case '--prefix':
				opts.prefix = need(i, arg)
				i++
				break
			case '--java':
				opts.java = need(i, arg)
				i++
				break
			case '--username':
				opts.username = need(i, arg)
				i++
				break
			case '--no-username':
				opts.username = ''
				break
			case '--baritone':
				opts.baritone = need(i, arg)
				i++
				break
			case '--baritone-version':
				opts.baritoneVersion = need(i, arg)
				i++
				break
			case '--token':
				opts.token = need(i, arg)
				opts.requireToken = true
				i++
				break
			case '--no-token':
				opts.requireToken = false
				opts.token = null
				break
			case '--clean':
				opts.clean = true
				break
			case '--no-build':
				opts.build = false
				break
			case '--restart':
				opts.restart = true
				break
			case '--verbose':
				opts.verbose = true
				break
			case '--no-wait':
				opts.wait = false
				break
			case '--timeout':
				opts.timeout = Number(need(i, arg))
				i++
				break
			case '--gl':
				opts.gl = need(i, arg) as GlMode
				i++
				break
			case '--refresh':
				opts.refresh = true
				break
			case '-h':
			case '--help':
				opts.help = true
				break
			default:
				fail(`Opcion desconocida: ${arg} (--help para la lista)`)
		}
	}

	if (!Number.isInteger(opts.instances) || opts.instances < 1) fail('--instances debe ser >= 1')
	if (!Number.isInteger(opts.portBase) || opts.portBase < 1 || opts.portBase > 65535) fail('--port-base fuera de rango')
	if (!['auto', 'software', 'hardware'].includes(opts.gl)) fail('--gl debe ser auto, software o hardware')
	return opts
}

const help = (): void => {
	console.log(`
Instancias de Minecraft con mc-puppeteer (y Baritone) en paralelo.

  bun   scripts/run-instances.ts -n 3
  node scripts/run-instances.ts -n 3

Opciones:
  -n, --instances N       Instancias a lanzar (def. 2)
  -p, --port-base P       Primer puerto HTTP; si esta ocupado usa el siguiente (def. 25580)
      --host H            Host del servidor HTTP del mod (def. 127.0.0.1)
  -m, --memory Xmx        Memoria por instancia (def. 2G)
  -w, --width W           Ancho de ventana (def. 854)
      --height H          Alto de ventana (def. 480)
      --dir D             Carpeta de instancias (def. run-instances)
      --prefix NAME       Prefijo de instancia (def. mc)
      --java PATH         Ruta del ejecutable de java (def. el del PATH)
      --username NICK     Nombre de jugador (def. el de la instancia: mc1, mc2...)
      --no-username       Que elija el juego un nombre aleatorio (Player372...)
      --baritone ESPEC    auto | none | ruta/al/baritone.jar | URL (def. auto)
      --baritone-version V Version de Baritone (def. ${BARITONE_VERSION}, la de MC 1.21.5)
      --token T           Exige ese token en todas (def. sin token: no se manda)
      --clean             Borra las carpetas de instancia antes de arrancar
      --no-build          No compila antes de arrancar
      --restart          Reinicia las instancias que mueran
      --gl MODO           auto | software | hardware (def. auto)
      --timeout SEG       Espera maxima por instancia (def. 240)
      --no-wait           No espera a que el mod responda
      --refresh           Vuelve a pedir el run config a Gradle
      --verbose           Muestra toda la salida, no solo lo interesante

Las instancias se lanzan con PUPPETEER_BARITONE_ASYNC=1, que es lo que permite
que #mine y #goto <bloque> no cuelguen el hilo principal del juego. Para el
comportamiento antiguo, exporta PUPPETEER_BARITONE_ASYNC=0 antes de lanzarlo.

  -h, --help              Esto
`)
}

// --------------------------------------------------------------------------- gradle

const runCommand = (command: string, args: string[], cwd: string, label: string): Promise<number> => {
	say(`$ ${command} ${args.join(' ')}   (${label})`)
	return new Promise(done => {
		const res = spawn(command, args, { cwd, stdio: 'inherit' })
		res.on('error', error => {
			warn(`No se pudo ejecutar ${command}: ${error.message}`)
			done(127)
		})
		res.on('exit', (code, signal) => done(code ?? (signal ? 1 : 0)))
	})
}

const gradlew = (): string => (process.platform === 'win32' ? 'gradlew.bat' : './gradlew')

const ensureBuild = async (opts: Options, projectDir: string): Promise<void> => {
	if (!opts.build) {
		say('Compilacion omitida (--no-build)')
		return
	}
	if ((await runCommand(gradlew(), ['build', '-x', 'test'], projectDir, 'compilando el mod')) !== 0) {
		fail('El build de Gradle ha fallado')
	}
}

const ensureRunConfig = async (opts: Options, projectDir: string): Promise<RunConfig> => {
	const target = join(projectDir, 'scripts', '.run-config.json')
	if (!opts.refresh) {
		const cached = readJson<RunConfig>(target)
		if (cached?.classpath?.length && cached.mainClass) {
			say(`Run config reutilizada (${target})`)
			return cached
		}
	}
	say('Preguntando a Gradle como se arranca el cliente...')
	const code = await runCommand(
		gradlew(),
		['-I', 'scripts/gradle-run-config.init.gradle', 'dumpRunConfig'],
		projectDir,
		'volcando el run config',
	)
	if (code !== 0) fail('El volcado del run config ha fallado')
	const cfg = readJson<RunConfig>(target)
	if (!cfg?.classpath?.length || !cfg.mainClass) fail(`El run config no es utilizable: ${target}`)
	return cfg
}

// --------------------------------------------------------------------------- classpath

/**
 * Java no admite un classpath gigante en la linea de comandos de forma fiable
 * (limite de 32k en Windows), asi que se escribe un argfile: exactamente lo que
 * hace Loom para `runClient`.
 */
const writeClasspathArgFile = (cfg: RunConfig, projectDir: string): string => {
	const joined = cfg.classpath.join(delimiter)
	const digest = createHash('sha256').update(joined).digest('hex').slice(0, 12)
	const path = join(projectDir, 'scripts', '.cache', `classpath-${digest}.txt`)
	ensureDir(dirname(path))

	const quote = (token: string): string =>
		/[ \t\n\r"'#\\]/.test(token) ? `"${token.replace(/([\\"])/g, '\\$1')}"` : token

	const body = ['-classpath', cfg.classpath.map(quote).join(delimiter), ''].join('\n')
	if (!existsSync(path) || readFileSync(path, 'utf8') !== body) writeFileSync(path, body, 'utf8')
	say(`Classpath: ${cfg.classpath.length} entradas -> ${path}`)
	return path
}

// --------------------------------------------------------------------------- baritone

const download = async (url: string, target: string): Promise<void> => {
	say(`Descargando Baritone: ${url}`)
	const response = await fetch(url, { redirect: 'follow' })
	if (!response.ok) fail(`No se pudo descargar Baritone (HTTP ${response.status})`)
	ensureDir(dirname(target))
	writeFileSync(target, Buffer.from(await response.arrayBuffer()))
}

/**
 * Baritone va al `mods/` de cada instancia, nunca a `~/.minecraft`.
 * Se descarga una sola vez a `scripts/.cache/` y de ahi se copia a cada una.
 */
const ensureBaritone = async (opts: Options, projectDir: string): Promise<string | null> => {
	if (opts.baritone === 'none') {
		say('Baritone omitido (--baritone none)')
		return null
	}

	const cacheDir = join(projectDir, 'scripts', '.cache')

	if (opts.baritone.startsWith('http://') || opts.baritone.startsWith('https://')) {
		const target = join(cacheDir, `baritone-${opts.baritoneVersion}.jar`)
		if (!existsSync(target)) await download(opts.baritone, target)
		return target
	}

	if (opts.baritone !== 'auto') {
		const local = resolve(opts.baritone)
		if (!existsSync(local)) fail(`No existe el jar de Baritone: ${local}`)
		say(`Baritone: ${local}`)
		return local
	}

	const version = opts.baritoneVersion
	const target = join(cacheDir, `baritone-standalone-fabric-${version}.jar`)
	const expected = BARITONE_SHA256[version] ?? null
	if (!existsSync(target)) await download(baritoneUrl(version), target)

	if (expected) {
		const actual = sha256(target)
		if (actual === expected) say(`Baritone v${version} verificado (sha256 correcto)`)
		else warn(`Baritone v${version}: sha256 ${actual} distinto del oficial; se usa igualmente.`)
	} else {
		warn(`Sin sha256 conocido para Baritone v${version}: revisa el jar a mano.`)
	}
	return target
}

const installBaritone = (jar: string, gameDir: string): string => {
	const target = join(ensureDir(join(gameDir, 'mods')), basename(jar))
	if (existsSync(target)) rmSync(target, { force: true })
	// Copia y no enlace duro: las instancias pueden vivir en otro disco y MC
	// no debe ver el mismo jar desde dos sitios.
	writeFileSync(target, readFileSync(jar))
	return target
}

// --------------------------------------------------------------------------- configuracion

const writeInstanceConfig = (inst: Instance): void => {
	const path = join(inst.dir, 'config', 'mc-puppeteer.json')
	const current = readJson<Record<string, unknown>>(path) ?? {}
	writeJsonAtomic(path, {
		enabled: true,
		...current,
		// El puerto y el token los manda este script, siempre.
		host: inst.host,
		port: inst.port,
		requireToken: inst.requireToken,
		authToken: inst.requireToken ? inst.token : '',
	})
}

/**
 * `options.txt` minimo, y solo si no existe: MC rellena el resto con sus
 * valores por defecto respetando lo que haya.
 *
 * Lo que importa con N instancias a la vez es `pauseOnLostFocus:false` (que una
 * ventana en segundo plano no pause el juego), el tope de FPS y el sonido.
 */
const writeOptions = (gameDir: string): void => {
	const path = join(gameDir, 'options.txt')
	if (existsSync(path)) return
	writeFileSync(
		path,
		[
			'pauseOnLostFocus:false',
			'maxFps:60',
			'renderDistance:4',
			'simulationDistance:5',
			'guiScale:1',
			'narrator:0',
			'fullscreen:false',
			'soundCategory_master:0.0',
			'',
		].join('\n'),
		'utf8',
	)
}

// --------------------------------------------------------------------------- plan

/**
 * Registro de lo que hay levantado, para que otras herramientas (el TUI de
 * `scripts/tui.ts`) no tengan que adivinar puertos.
 */
const writeRegistry = (opts: Options, projectDir: string, planned: Instance[]): void => {
	const root = resolve(projectDir, opts.dir)
	const path = join(root, '.instances.json')
	writeJsonAtomic(path, {
		generatedAt: new Date().toISOString(),
		dir: root,
		instances: planned.map(inst => ({
			name: inst.name,
			host: inst.host,
			port: inst.port,
			token: inst.requireToken ? inst.token : '',
			requireToken: inst.requireToken,
			gameDir: inst.dir,
			log: inst.logFile,
		})),
	})
	say(`Registro de instancias: ${path}`)
}

const planInstances = async (opts: Options, projectDir: string, baritone: string | null): Promise<Instance[]> => {
	const root = ensureDir(resolve(projectDir, opts.dir))
	const taken = new Set<number>()
	const planned: Instance[] = []

	for (let i = 1; i <= opts.instances; i++) {
		const name = `${opts.prefix}${i}`
		const wanted = opts.portBase + i - 1
		const dir = uniqueDir(root, name, opts.clean)
		const port = await pickPort(wanted, opts.host, taken)

		if (port !== wanted) say(`  ${name}: el puerto ${wanted} esta ocupado, uso ${port}`)
		if (basename(dir) !== name) say(`  ${name}: ${join(root, name)} ya existia, uso ${basename(dir)}`)

		ensureDir(dir)
		writeOptions(dir)

		const inst: Instance = {
			name,
			dir,
			host: opts.host,
			port,
			// Sin token por defecto: el script no inventa tokens aleatorios ni
			// manda cabecera Authorization. Solo si se pasa --token.
			token: opts.token ?? '',
			requireToken: opts.requireToken,
			proc: null,
			restarts: 0,
			ready: false,
			logFile: join(dir, 'logs', 'stdout.log'),
		}
		writeInstanceConfig(inst)
		if (baritone) say(`  ${name}: baritone en ${installBaritone(baritone, dir)}`)
		planned.push(inst)
	}

	return planned
}

// --------------------------------------------------------------------------- arranque

const resolveJava = (opts: Options): string => {
	if (opts.java) return opts.java
	const home = process.env.JAVA_HOME
	if (home) {
		const candidate = join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
		if (existsSync(candidate)) return candidate
	}
	return 'java'
}

/** `auto` = OpenGL por software cuando no hay display (servidor, CI, SSH). */
const glEnv = (mode: GlMode): Record<string, string> => {
	const hasDisplay = Boolean(process.env.DISPLAY || process.env.WAYLAND_DISPLAY)
	const software = mode === 'software' || (mode === 'auto' && !hasDisplay)
	return software ? { LIBGL_ALWAYS_SOFTWARE: '1' } : {}
}

/**
 * Cada instancia entra con su propio nombre de jugador (`mc1`, `mc2`...), que es
 * lo que hace que el servidor las trate como jugadores distintos: el UUID offline
 * lo deriva el juego del nombre.
 */
const javaArgs = (opts: Options, inst: Instance, cfg: RunConfig, argFile: string): string[] => {
	const username = opts.username === null ? inst.name : opts.username
	return [
		`-Xmx${opts.memory}`,
		...cfg.jvmArgs,
		`@${argFile}`,
		cfg.mainClass,
		...cfg.programArgs,
		...(username ? ['--username', username] : []),
		'--width',
		String(opts.width),
		'--height',
		String(opts.height),
	]
}

/** Con N clientes hablando a la vez, a consola solo sale lo que interesa. */
const INTERESTING =
	/\[mc-puppeteer|Setting user|Loading .*? mods|Baritone|OpenGL|Backend library|GLFW|\bERROR\b|\bWARN\b|Exception|Failed|Caused by/i

const pumpOutput = (inst: Instance, opts: Options, stream: OutputStream): void => {
	ensureDir(dirname(inst.logFile))
	const log = (line: string): void => {
		writeFileSync(inst.logFile, `${line}\n`, { flag: 'a' })
		if (opts.verbose || INTERESTING.test(line)) console.log(`${stamp()}  [${inst.name}] ${line}`)
	}
	let buffer = ''
	stream.setEncoding?.('utf8')
	stream.on('data', chunk => {
		buffer += chunk.toString()
		const lines = buffer.split('\n')
		buffer = lines.pop() ?? ''
		for (const line of lines) log(line)
	})
	stream.on('end', () => {
		if (buffer.trim()) log(buffer)
	})
}

let stopping = false
const running: Instance[] = []

const launch = (inst: Instance, opts: Options, cfg: RunConfig, argFile: string): void => {
	ensureDir(dirname(inst.logFile))
	writeFileSync(inst.logFile, `--- ${new Date().toISOString()} ${inst.name} ---\n`, { flag: 'a' })

	// El directorio de trabajo ES el gameDir: el loader usa el CWD cuando no
	// le pasan `--gameDir` (que es el caso del arranque de desarrollo de Loom).
	//
	// `PUPPETEER_BARITONE_ASYNC=1` va por defecto en todas las instancias: sin
	// ella, `#mine` y `#goto <bloque>` dejan el hilo principal de Minecraft
	// colgado para siempre, porque Baritone espera desde el propio hilo principal
	// un `CompletableFuture` que solo ese hilo puede completar (ver
	// `agents/05-api.md`). Va primero en el objeto para que se pueda anular
	// exportando `PUPPETEER_BARITONE_ASYNC=0` o poniendolo en el `env` de
	// `scripts/.run-config.json`: lo que viene detras pisa.
	const proc = spawn(resolveJava(opts), javaArgs(opts, inst, cfg, argFile), {
		cwd: inst.dir,
		env: { PUPPETEER_BARITONE_ASYNC: '1', ...process.env, ...cfg.env, ...glEnv(opts.gl) },
		stdio: ['ignore', 'pipe', 'pipe'],
	})
	inst.proc = proc
	if (proc.stdout) pumpOutput(inst, opts, proc.stdout as OutputStream)
	if (proc.stderr) pumpOutput(inst, opts, proc.stderr as OutputStream)
	proc.on('error', error => warn(`[${inst.name}] no se pudo lanzar java: ${error.message}`))
	proc.on('exit', (code, signal) => {
		inst.proc = null
		inst.ready = false
		if (!stopping) say(`[${inst.name}] ha salido (codigo ${code ?? signal}); log en ${inst.logFile}`)
	})
}

// --------------------------------------------------------------------------- salud

const probe = async (inst: Instance, timeoutMs: number): Promise<boolean> => {
	try {
		const response = await fetch(`http://${inst.host}:${inst.port}/puppeteer/health`, {
			signal: AbortSignal.timeout(timeoutMs),
		})
		return response.ok
	} catch {
		return false
	}
}

const waitReady = async (inst: Instance, opts: Options): Promise<void> => {
	const deadline = Date.now() + opts.timeout * 1000
	while (Date.now() < deadline) {
		if (inst.proc === null) return
		if (await probe(inst, 1500)) {
			inst.ready = true
			return
		}
		await new Promise(done => setTimeout(done, 500))
	}
	warn(`[${inst.name}] no ha respondido /health en ${opts.timeout}s (log en ${inst.logFile})`)
}

// --------------------------------------------------------------------------- parada

const killTree = (inst: Instance, signal: NodeJS.Signals = 'SIGTERM'): void => {
	const proc = inst.proc
	if (!proc || proc.exitCode !== null) return
	if (process.platform === 'win32' && proc.pid) {
		spawn('taskkill', ['/pid', String(proc.pid), '/T', '/F'], { stdio: 'ignore' })
		return
	}
	try {
		proc.kill(signal)
	} catch {
		/* ya estaba muerto */
	}
}

const stopAll = async (): Promise<void> => {
	stopping = true
	const alive = running.filter(inst => inst.proc)
	if (alive.length === 0) return
	say(`Parando ${alive.length} instancia(s)...`)
	for (const inst of alive) killTree(inst)
	await new Promise(done => setTimeout(done, 6000))
	for (const inst of running) {
		if (inst.proc) {
			killTree(inst, 'SIGKILL')
			inst.proc = null
		}
	}
	say('Paradas')
}

const onSignal = (): void => {
	void stopAll().then(() => process.exit(0))
}

// --------------------------------------------------------------------------- main

const main = async (): Promise<void> => {
	const opts = parseArgs(process.argv.slice(2))
	if (opts.help) {
		help()
		return
	}

	const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), '..')
	say(`Proyecto: ${projectDir}`)
	say(`${opts.instances} instancia(s) desde el puerto ${opts.portBase}, carpeta ${opts.dir}`)

	await ensureBuild(opts, projectDir)
	const cfg = await ensureRunConfig(opts, projectDir)
	const argFile = writeClasspathArgFile(cfg, projectDir)
	const baritone = await ensureBaritone(opts, projectDir)
	const planned = await planInstances(opts, projectDir, baritone)
	running.push(...planned)
	writeRegistry(opts, projectDir, planned)

	say('')
	for (const inst of planned) {
		say(`${inst.name}  http://${inst.host}:${inst.port}/puppeteer  ${inst.dir}`)
		if (inst.requireToken) say(`  token: ${inst.token}`)
	}
	say('')

	for (const inst of planned) launch(inst, opts, cfg, argFile)

	if (opts.wait) {
		say(`Esperando a que el mod responda /health (hasta ${opts.timeout}s)...`)
		await Promise.all(planned.map(inst => waitReady(inst, opts)))
		say(`${planned.filter(inst => inst.ready).length}/${planned.length} instancia(s) listas`)
		for (const inst of planned) {
			if (!inst.ready) continue
			const auth = inst.requireToken ? ` -H 'Authorization: Bearer ${inst.token}'` : ''
			say(`  curl -s http://${inst.host}:${inst.port}/puppeteer/status${auth}   # ${inst.name}`)
		}
	}

	if (opts.restart) {
		setInterval(() => {
			for (const inst of running) {
				if (inst.proc || stopping) continue
				inst.restarts++
				say(`[${inst.name}] reiniciando (intento ${inst.restarts})`)
				launch(inst, opts, cfg, argFile)
			}
		}, 2000)
	}

	say('Ctrl-C para parar todas las instancias.')
	await new Promise<void>(done => {
		const watch = setInterval(() => {
			if (running.every(inst => inst.proc === null)) {
				clearInterval(watch)
				done()
			}
		}, 1000)
	})
	say('Todas las instancias han salido.')
	process.exit(0)
}

process.on('SIGINT', onSignal)
process.on('SIGTERM', onSignal)

void main().catch(error => {
	console.error(`${stamp()}  ERROR  ${error instanceof Error ? (error.stack ?? error.message) : String(error)}`)
	process.exit(1)
})
