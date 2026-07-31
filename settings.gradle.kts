rootProject.name = "pet-marketplace"

// excel-import живёт отдельным чекаутом рядом с репозиторием (`../projects/excel` от корня
// репозитория). Git-worktree переносит корень на несколько уровней глубже
// (`.claude/worktrees/<name>`), поэтому фиксированный относительный путь там не разрешается —
// ищем `projects/excel` вверх по предкам корня. Путь можно задать явно свойством
// `excelImportPath` (gradle.properties или -PexcelImportPath=...).
val excelImportBuild: File = providers.gradleProperty("excelImportPath").orNull
        ?.let { File(it) }
        ?: generateSequence(settingsDir.absoluteFile) { it.parentFile }
                .map { File(it, "projects/excel") }
                .firstOrNull { it.isDirectory }
        ?: error(
                "excel-import checkout not found: no `projects/excel` directory in any ancestor of "
                        + "$settingsDir. Clone it or set -PexcelImportPath=/path/to/excel.")

includeBuild(excelImportBuild)
