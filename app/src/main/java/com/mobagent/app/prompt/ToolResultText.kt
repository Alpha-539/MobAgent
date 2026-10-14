package com.mobagent.app.prompt

/**
 * Asbob (tool) natijalari foydalanuvchiga o'zbekcha ko'rsatiladi, lekin MODELGA
 * yuborilganda inglizchaga o'giriladi. Sabab: kichik model prompt ichida
 * o'zbekcha so'zlarni ("Xato: fayl topilmadi") ko'rsa, javobini ham
 * aralash tilda yozib qo'yadi. UI matnlari o'zgarmaydi — faqat modelga ketadigan nusxa.
 */
private val RULES: List<Pair<Regex, String>> = listOf(
    Regex("""Natija \(kod (\d+)\)""") to "Result (exit code $1)",
    Regex("""Xato: find matni (\d+) marta uchraydi""") to "Error: find text occurs $1 times",
    Regex("""Xato: find matni bir necha joyga mos keladi""") to "Error: find text matches several places",
    Regex("""Xato: find matni faylda topilmadi""") to "Error: find text not found in the file",
    Regex("""Xato: find bo'sh""") to "Error: find is empty",
    Regex("""Xato: fayl juda katta \((\d+) bayt\)""") to "Error: file too large ($1 bytes)",
    Regex("""Xato: fayl topilmadi""") to "Error: file not found",
    Regex("""Xato: papka topilmadi""") to "Error: folder not found",
    Regex("""Xato: bu papka emas""") to "Error: this is not a folder",
    Regex("""Xato: bu papkani o'chirib bo'lmaydi""") to "Error: this folder cannot be deleted",
    Regex("""Xato: bu papkani ko'chirib bo'lmaydi""") to "Error: this folder cannot be moved",
    Regex("""Xato: nishon allaqachon mavjud""") to "Error: destination already exists",
    Regex("""Xato: o'chirib bo'lmadi""") to "Error: could not delete",
    Regex("""Xato: ko'chirib bo'lmadi""") to "Error: could not move",
    Regex("""Xato: topilmadi""") to "Error: not found",
    Regex("""Xato: (path|content|to) bo'sh""") to "Error: $1 is empty",
    Regex("""Xato: buyruq bo'sh""") to "Error: command is empty",
    Regex("""Xato:""") to "Error:",
    Regex("""\[kesildi: jami (\d+) belgi\]""") to "[truncated: $1 characters total]",
    Regex(""" yozildi \((\d+) qator\)""") to " written ($1 lines)",
    Regex(""" tahrirlandi""") to " edited",
    Regex(""" \(bo'sh joylar farqi e'tiborga olinmadi\)""") to " (whitespace differences ignored)",
    Regex(""" o'chirildi""") to " deleted",
    Regex(""" bo'sh$""") to " is empty",
    Regex("""\((\d+) bayt\)""") to "($1 bytes)",
    Regex("""\.\.\. yana (\d+) ta""") to "... $1 more",
)

/** Modelga yuboriladigan nusxa: o'zbekcha tizim matnlari inglizchaga almashtiriladi (fayl mazmuniga tegilmaydi). */
fun String.toEnglishForModel(): String {
    var out = this
    for ((re, rep) in RULES) out = re.replace(out, rep)
    return out
}
