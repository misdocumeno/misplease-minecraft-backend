import { chooseJava, declaredJava, minecraftJava } from "./jdk.ts"

const cases = [
  ["1.16.5", 8],
  ["1.17.1", 16],
  ["1.18.2", 17],
  ["1.20.1", 17],
  ["1.20.4", 17],
  ["1.20.5", 21],
  ["1.21.11", 21],
  ["26.1", 25],
]

for (const [version, expected] of cases) {
  const got = minecraftJava(version)
  if (got !== expected) throw new Error(`${version} -> ${got}, expected ${expected}`)
}

const silentGradle85 = chooseJava([], { min: 8, max: 21 }, "8.5")
if (silentGradle85 !== 21) throw new Error(`gradle 8.5 silent launcher ${silentGradle85}`)
const silentGradle84 = chooseJava([], { min: 8, max: 19 }, "8.4")
if (silentGradle84 !== 17) throw new Error(`gradle 8.4 silent launcher ${silentGradle84}`)
const declared17 = chooseJava([17], { min: 8, max: 21 }, "8.5")
if (declared17 !== 17) throw new Error(`declared 17 became ${declared17}`)
const mavenServer = chooseJava([17], undefined, undefined)
if (mavenServer !== 17) throw new Error(`maven server launcher ${mavenServer}`)

const kotlin = declaredJava('kotlinOptions.jvmTarget = "17"')
if (!kotlin.includes(17)) throw new Error(`jvmTarget not detected: ${kotlin}`)
const language = declaredJava("JavaLanguageVersion.of(17)")
if (!language.includes(17)) throw new Error(`toolchain not detected: ${language}`)

function bytecode(launcher, declared, server, explicit) {
  const projectDeclared = declared.length > 0 || explicit
  return !projectDeclared && server !== undefined && launcher > server ? server : undefined
}

if (bytecode(21, [], 17, false) !== 17) throw new Error("silent gradle on 1.20.1 should target 17")
if (bytecode(21, [17], 17, false) !== undefined) throw new Error("declared target must stay")
if (bytecode(17, [], 21, false) !== undefined) throw new Error("must not raise the target")
if (bytecode(21, [], 17, true) !== undefined) throw new Error("java: override must stay")
if (bytecode(17, [], 17, false) !== undefined) throw new Error("matching launcher needs no cap")

console.log("java cap checks passed")
