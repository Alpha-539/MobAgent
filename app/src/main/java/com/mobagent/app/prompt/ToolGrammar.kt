package com.mobagent.app.prompt

/**
 * llama.cpp GBNF grammatikasi: agent rejimida model FAQAT quyidagilardan
 * birini yoza oladi:
 *  1) oddiy javob (matn; birinchi belgisi `{` bo'lmasligi shart), yoki
 *  2) [bir qatorli izoh + yangi qator] + BITTA yaroqli tool-call JSON.
 * JSON tugagach hech narsa yozib bo'lmaydi (faqat generatsiya tugashi).
 *
 * Kafolatlanadigan narsalar: sintaksis xatosi yo'q, kod-fence yo'q, JSON'dan
 * keyin ortiqcha matn yo'q, action faqat ro'yxatdagi nomlardan, har action
 * uchun kerakli kalitlar to'liq va aniq tartibda. Satr ichida xom yangi qator
 * yo'q (u `\\n` bo'lib yoziladi) — bu JSON'ni buzilishdan saqlaydi.
 *
 * Ikki variant: GRAMMAR (3B+, erkin `execute_bash` ham bor) va COMPACT
 * (1.5B-2B: faqat fayl asboblari — model bash sintaksisini yozmaydi, faqat
 * yo'l/matn to'ldiradi).
 *
 * Yangi tool qo'shsangiz: (1) `rules`ga uning qoidasini yozing, (2) `call`
 * qatoriga nomini qo'shing, (3) ToolCall va AgentOrchestrator.executeApprovedTool'ni yangilang.
 */
object ToolGrammar {
    private val COMMON = """
root ::= tool-turn | chat-turn
tool-turn ::= (pre "\n")? tool
chat-turn ::= [^{\n\x00] [^\x00]*
pre ::= [^{\n\x00]+
tool ::= "{" ws "\"action\"" ws ":" ws call ws "}"
write ::= "\"write_file\"" sep "\"path\"" kv string sep "\"content\"" kv string
edit ::= "\"edit_file\"" sep "\"path\"" kv string sep "\"find\"" kv string sep "\"replace\"" kv string
listdir ::= "\"list_dir\"" sep "\"path\"" kv string
readf ::= "\"read_file\"" sep "\"path\"" kv string
delete ::= "\"delete\"" sep "\"path\"" kv string
rename ::= "\"rename\"" sep "\"path\"" kv string sep "\"to\"" kv string
sep ::= ws "," ws
kv ::= ws ":" ws
ws ::= " "?
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
""".trimIndent()

    private const val BASH_RULE = "bash ::= \"\\\"execute_bash\\\"\" sep \"\\\"cmd\\\"\" kv string\n"

    /** 3B+ modellar: barcha asboblar, jumladan execute_bash. */
    val GRAMMAR: String = COMMON + "\n" + BASH_RULE +
        "call ::= bash | write | edit | listdir | readf | delete | rename\n"

    /** 1.5B-2B modellar: faqat fayl asboblari (bash yo'q). */
    val COMPACT: String = COMMON + "\n" +
        "call ::= write | edit | listdir | readf | delete | rename\n"
}
