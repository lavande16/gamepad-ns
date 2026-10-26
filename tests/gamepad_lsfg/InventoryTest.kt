import java.nio.file.Files
import org.yuzu.yuzu_emu.utils.GameFoldPackage
fun main() {
    val root = Files.createTempDirectory("lsfg-inventory").toFile()
    try {
        val system = root.resolve("system").apply { mkdirs() }
        system.resolve("prod.keys").writeText("keys")
        val before = GameFoldPackage.inventorySignature(root)
        val dll = system.resolve("Lossless.dll")
        dll.writeText("version-one")
        check(GameFoldPackage.inventorySignature(root) == before) { "Adding LSFG must not trigger firmware/mod/save assembly" }
        dll.writeText("version-two-longer")
        check(GameFoldPackage.inventorySignature(root) == before) { "Replacing LSFG must not trigger assembly" }
        dll.delete()
        check(GameFoldPackage.inventorySignature(root) == before) { "Removing LSFG must not trigger assembly" }
        val save = root.resolve("games/game/saves/runtime/user/slot.sav")
        save.parentFile!!.mkdirs(); save.writeText("saved-game")
        check(GameFoldPackage.inventorySignature(root) == before) { "Runtime saves remain outside inventory" }
        system.resolve("prod.keys").writeText("new-keys")
        check(GameFoldPackage.inventorySignature(root) != before) { "Actual material changes still trigger assembly" }
        println("PASS 5 assembly-inventory assertions")
    } finally { root.deleteRecursively() }
}
