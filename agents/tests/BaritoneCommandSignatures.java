/*
 * Firmas de comandos de Baritone, extraidas de su codigo fuente.
 *
 * Este fichero NO se invoca desde la prueba de humo: es un testigo. Su unico
 * proposito es que el listado se pueda contrastar a ojo, y sobre todo para
 * documentar de donde sale cada nombre.
 *
 * Fuente: cabaletta/baritone, rama 1.21.4
 *   src/main/java/baritone/command/defaults/*Command.java   -> super(baritone, "nombre", "alias"...)
 *   src/main/java/baritone/command/defaults/DefaultCommands.java   -> CommandAlias y createAll()
 *   src/main/java/baritone/command/defaults/ExecutionControlCommands.java -> pause/resume/paused/cancel
 *   src/main/java/baritone/command/defaults/SelCommand.java       -> CLEARAREA("cleararea", "ca")
 *
 * Version verificada: v1.20.0 (la que trae Minecraft 26.3). El jar de release
 * de la API no incluye las implementaciones, asi que la lista sale del source.
 *
 * Que se comprobo y por que importa:
 *
 * - No existe `StopCommand`: `stop` es ALIAS de `cancel`
 *     new Command(baritone, "cancel", "c", "stop")
 * - No existe `ClearAreaCommand`: `cleararea` es SUBCOMANDO de `sel`
 *     CLEARAREA("cleararea", "ca")
 * - No existe `ModifiedCommand`: es un CommandAlias de `set`
 *     Arrays.asList("modified", "mod", "baritone", "modifiedsettings")
 * - `schematica` esta COMENTADO en DefaultCommands y no esta disponible
 * - `forcecancel` (ForceCancelCommand) es DISTINTO de `cancel`
 * - `thisway` exige 1 argumento: requireExactly(1)
 * - `path`, `invert`, `blacklist`, `click`, `elytra` aceptan 0: requireMax(0)
 *
 * Tabla de requires (sacada de los requireMin/requireMax/requireExactly):
 *
 *   comando        requiresArgs   detalle
 *   -----------    ------------   ---------------------------------------
 *   help           no
 *   set            si             requireMax(1) y luego requireMin(1)
 *   modified       no             alias
 *   reset          no             alias -> set reset
 *   goal           no
 *   goto           no
 *   path           no             requireMax(0)
 *   proc           no
 *   eta            no
 *   version        no
 *   repack         no             alias rescan
 *   build          si
 *   litematica     no
 *   come           no
 *   axis           no             alias highway; la API pide 'y' explicitamente
 *   forcecancel    no
 *   gc             no
 *   invert         no             requireMax(0)
 *   tunnel         si
 *   render         no
 *   farm           si             requireMax(2)
 *   follow         si
 *   pickup         si
 *   explorefilter  si             requireMax(2)
 *   reloadall      no
 *   saveall        no
 *   explore        si             admite cero: explora desde el jugador
 *   blacklist      no             requireMax(0)
 *   find           si
 *   mine           si
 *   click          no             requireMax(0)
 *   surface        no             alias top
 *   thisway        si             requireExactly(1), alias forward
 *   waypoints      si             alias waypoint/wp
 *   sethome        no             alias -> waypoints save home
 *   home           no             alias -> waypoints goto home
 *   sel            si             alias selection/s
 *   elytra         no
 *   pause          no             alias p/paws
 *   resume         no             alias r/unpause/unpaws
 *   paused         no
 *   cancel         no             alias c/stop
 *
 * Comprobado ademas en el binario, no solo en el source: el mixin
 * `MixinClientPlayNetHandler` de baritone-api-fabric-1.20.0.jar inyecta en
 *
 *   @Mixin(ClientPacketListener.class)
 *   @Inject(method = "sendChat(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
 *
 * Es decir: en la CABECERA de `sendChat`, que es justo lo que llama
 * `ClientBridge.sendChat`, y es cancelable, asi que un `#comando` no llega al
 * servidor. Esta es la razon de que /baritone/* use sendChat y no sendCommand.
 */
public final class BaritoneCommandSignatures {

	private BaritoneCommandSignatures() {
	}

	/** Nombres canonicos, en el orden de `DefaultCommands.createAll()`. */
	public static final String[] CANONICAL = {
		"help", "set", "modified", "reset", "goal", "goto", "path", "proc", "eta",
		"version", "repack", "build", "litematica", "come", "axis", "forcecancel",
		"gc", "invert", "tunnel", "render", "farm", "follow", "pickup",
		"explorefilter", "reloadall", "saveall", "explore", "blacklist", "find",
		"mine", "click", "surface", "thisway", "waypoints", "sethome", "home",
		"sel", "elytra",
		// ExecutionControlCommands, anadidos aparte por createAll()
		"pause", "resume", "paused", "cancel",
	};

	/** Alias aceptados por el gestor de comandos. */
	public static final String[] ALIASES = {
		"?", "setting", "settings", "mod", "baritone", "modifiedsettings",
		"rescan", "highway", "top", "forward", "waypoint", "wp",
		"selection", "s", "p", "paws", "r", "unpause", "unpaws", "c", "stop",
	};

	/** Comandos que no aceptan llamarse sin argumentos. */
	public static final String[] REQUIRE_ARGUMENTS = {
		"set", "build", "tunnel", "farm", "follow", "pickup", "explorefilter",
		"explore", "find", "mine", "thisway", "waypoints", "sel",
	};

	/**
	 * Comandos que NO estan disponibles, pese a que parece que si.
	 * `schematica` esta comentado en DefaultCommands; los demas nunca existieron.
	 */
	public static final String[] UNAVAILABLE = {
		"schematica", "cleararea", "stopcommand",
	};
}
