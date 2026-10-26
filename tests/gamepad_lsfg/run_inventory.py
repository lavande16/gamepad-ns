#!/usr/bin/env python3
"""Run the actual Kotlin material inventory without Gradle or native builds."""
import os
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[2]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
def jar(group, artifact, version='*'):
    return sorted((cache / group / artifact).glob(f'{version}/*/*.jar'))[-1]
java = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')) / 'bin/java'
compiler = jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.1.21')
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.21')
annotations = jar('org.jetbrains', 'annotations')
runtime = [compiler, stdlib, annotations, jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.1.21'), jar('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.1.21'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm'), jar('org.jetbrains.intellij.deps', 'trove4j')]
android = Path.home() / 'Library/Android/sdk/platforms/android-36/android.jar'
with tempfile.TemporaryDirectory(prefix='gamepad-lsfg-inventory-') as target:
    subprocess.run([str(java), '-cp', os.pathsep.join(map(str, runtime)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join(map(str, [stdlib, annotations, android])), '-d', target,
        str(root / 'src/android/app/src/main/java/org/yuzu/yuzu_emu/utils/GameFoldPackage.kt'), str(root / 'src/android/app/src/main/java/org/yuzu/yuzu_emu/utils/GamePadNsProductContract.kt'), str(root / 'tests/gamepad_lsfg/InventoryTest.kt')], check=True)
    subprocess.run([str(java), '-cp', os.pathsep.join(map(str, [target, stdlib, android])), 'InventoryTestKt'], check=True)
