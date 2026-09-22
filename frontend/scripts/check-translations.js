#!/usr/bin/env node
// QA per a fitxers PO de traduccions: detecta paraules enganxades per
// falta d'espai, placeholders trencats i estructures de plural perdudes.
//
// Usage:
//   node ./scripts/check-translations.js [-l <locale>] [--self-test]
//
// Exit: 0 sense errors (els avisos no fallen), 1 amb errors, 2 mal us.
// Cal executar-ho des de `frontend/` (com `translations.js`).

import getopts from "getopts";
import { promises as fs } from "node:fs";
import gt from "gettext-parser";

// Paraules funcionals curtes: si obren un token desconegut, gairebe
// segur que falta un espai (`del'equip`, `lapolitica`, `sinecessiteu`).
const FUNCIO = new Set(
  "i o a e de del dels la el els les lo un una uns unes al als en amb per pel pels que com no ni si ja se es ho hi li me te ne em et us vos ens son són és més mes tot tota molt tan tant on quan perquè pero però doncs fins entre sobre sota cap cada altre seva seu meva teu nostre vostre aquest aquesta això allò jo tu ell ella nosaltres vosaltres ells".split(
    " ",
  ),
);

// Marques que legitimen una unio minuscula+Majuscula.
const MARQUES_OK = [
  "GitHub",
  "GitLab",
  "YouTube",
  "InVision",
  "innerShadow",
  "dropShadow",
  "iOS",
  "macOS",
];

// Vocabulari comu (verbs, noms, adjectius frequents) per a validar
// la part no-funcional d'una possible particio. Sense dependencia
// externa: llista curada a ma. Si falta una paraula, el cas caura a
// `avis` en comptes de `error`, mai en silenci.
const PARAULES_COMUNES = new Set(
  "corregir poder obtenir mantindran targeta controladors importants futura donant tingui informar diversitat continuï juntament revocaran habilitar desament autenticació family aplicació còpies configurat necessita conflictes trigar res directament enviï correccions admet exportacions aplicacions càrrec envia enllaç actuals ajuda moment valor equips conjunt projecte fitxer compte usuari persona persones cosa temps part text nom contrasenya sessió idioma versió canvi arxiu error avís filtre cerca vista pestanya botó camp llista taula imatge forma capa fons color mida data correu propietari propietària membre opcions prova suprimireu interactuïn ajudarem quedi configurat permetre continueu breu manteniment present començar donant css font prioritzar suborganitzacions podreu poden facturació".split(
    " ",
  ),
);

// Paraules correctes que el divisor parteix en dues parts valides
// (`segura` -> `segur` + `a`). Revisades a ma; si el checker es queixa
// d'una paraula correcta nova, afegiu-la aqui.
const PARAULES_OK = new Set(
  "ajudarem aplana coincideixi coma comes comprova comprovar comprovar-ho comuna convidarem estarem existeixen existeixi fase gratuïta interna mateixa meves molta oberta obertes permeten permeti segura targeta teus usa usen niar negreta emplena emplenament edita selector atributs sobreescriuran compartides desenvolupadors administradors desbloquejar desbloquegeu opcionalment autoreferència especificant previsualització previsualitza predeterminat predeterminats predeterminada predefinides predefinida desactivades seleccionada seleccionades descarregueu centralitzat horitzontalment verticalment multijugador multiorganització complementari multiplicador actualitzarà activades activador cancel·laran comptaran configurar confirmar-ho conservaran desagrupa desbloqueja desenganxa despublica despublicar desselecciona-ho duplicades envia-ho importada importades interactius intercanvia lliscament lliuraran migracions mixte personalitzeu-les plantilles privadesa realitzant repositori restauraran selecciona-ho superposa transferiu unir-se usar-los autodesat demanar-ho desactivar-los abandonar-la accedir-hi accelereu activar-los actualitzeu adoptarà afegir-hi afegir-ne afegiu afegiu-ne agrupant-la ajudar-nos ajudar-vos ajudeu-nos ajudieu-nos ajustar-lo ajusteu ajustis alineació amplia ampliada amplieu animacions aplicant apliqueu arrossegueu assegurar-vos avançar-vos avisos baixades cancel·lada cancel·lant cercador coincidents col·laboració col·laborar commuteu compatibilitat concedir-hi consumeixin conèixer-vos definir-ne definiu depuració desbloca descarta-ho descriviu desemmascara deshabilita deshabilitada deshabilitades desplaçar desvincula dissenyeu editar-lo editen editeu eliminar-lo el·lipse el·lipses emplenats encabir-ho enfosqueix escriviu-nos espaiat espaiats especifiqueu estils expliqueu-nos exporta exportant exportar flexibilitat gaudireu il·limitat il·limitats il·lumina il·lustracions importats incrusta inhabilitada inicieu insereix inspecciona inspeccionar-ne instal·la instal·lació instal·lada instal·lades instal·lat instantànies integracions intentar-ho marca-ho migració milloreu milloreu-ho notificacions obsolet obteniu ometeu-ho omplir-ho opacitat orientar-vos personalitzada personalitzades pestanya porta-ho porta-retalls promoveu prototipar prototipatge publicar-la reassignar reassigneu remapejant remapeja remapejar silenciats sol·licitat sol·licitud sol·licituds suprimiran tipogràfic tipogràfica tipogràfics tipogràfiques torna-ho valorant vinculades vincular visiteu volteja unir-s'hi l'opacitat l'espaiat l'interlineat explora'n edita'l desant aquesta aquestes baixa capa capes compartir comproveu desa deseu dreta esteu files fixa inicia inicials meus només nous noves pel perfil perquè pes pla seus totes treballo una vosaltres vàlida vàlides desactiva desactivat desactivada desconnecta descobreix connectades alguna canvia compost comprovant del dels des enviarem mixtes noms quina senzilla uneix ves vista vés desconegut fixa pel pels tota comentari comunitat set desvinculat separades".split(
    " ",
  ),
);

const TOKEN_RE = /[\p{L}\p{M}]+(?:[·'’\-][\p{L}\p{M}]+)*/gu;
const SKIP_RE = /[%{@/\\=<>|#0-9]/;
const PLACEHOLDER_RES = [/%[sd]/g, /\{[^}]*\}/g, /%\([^)]*\)[sd]/g];
const PUNT_RE = /[,.:;!?…»)\]]([A-Za-zÀ-Úà-ú«("“‘$])/gu;
const CAMEL_RE = /[a-zàèéíòóúüç·]([A-ZÀÈÉÍÒÓÚÜ][a-zàèéíòóúü]+)/gu;
const PH_GLUED_RE = /%[sd](?=[A-Za-zÀ-Úà-ú])/gu;

function tokenitza(text) {
  return [...text.matchAll(TOKEN_RE)].map((m) => m[0].toLowerCase());
}

function baseApostrof(token) {
  const m = token.match(/^[ldsmntc]['’](.+)$/);
  return m ? m[1] : null;
}

function compte(text, re) {
  re.lastIndex = 0;
  return [...text.matchAll(re)].length;
}

// Validesa d'una PART d'una particio (mai la paraula sencera: un
// enganxat repetit no s'ha d'auto-validar perque surt molt).
function partValida(pal, freq) {
  return (
    FUNCIO.has(pal) ||
    PARAULES_OK.has(pal) ||
    PARAULES_COMUNES.has(pal) ||
    (freq.get(pal) ?? 0) >= 1
  );
}

// Totes les particions (esq, dre, regla) d'un token, incloent-hi
// cada segment separat per guionet.
function* particions(tok, freq) {
  const cands = [tok, ...tok.split("-")];
  const vistos = new Set();
  for (const c of cands) {
    if (c.length < 3) continue;
    for (let k = 1; k < c.length; k++) {
      const esq = c.slice(0, k);
      const dre = c.slice(k);
      if (esq.length < 1) continue;
      if (dre.length < 2 && !FUNCIO.has(dre)) continue;
      const clau = esq + "|" + dre;
      if (vistos.has(clau)) continue;
      vistos.add(clau);
      const dreBase = baseApostrof(dre) ?? dre;
      const dreOk = partValida(dreBase, freq);
      const esqOk = partValida(esq, freq);
      if (FUNCIO.has(esq) && dreOk) yield [esq, dre, "func-esq"];
      else if (FUNCIO.has(dre) && dre.length <= 4 && esqOk)
        yield [esq, dre, "func-dre"];
      else if (
        c.length >= 8 &&
        esq.length >= 3 &&
        dre.length >= 2 &&
        esqOk &&
        dreOk
      )
        yield [esq, dre, "contingut"];
    }
  }
}

const ORDRE = { "func-esq": 0, "func-dre": 1, contingut: 2 };

function millorParticio(tok, freq) {
  let millor = null;
  for (const [esq, dre, regla] of particions(tok, freq)) {
    if (esq.includes("-")) continue;
    if (
      !millor ||
      ORDRE[regla] < ORDRE[millor[2]] ||
      (ORDRE[regla] === ORDRE[millor[2]] && esq.length > millor[0].length)
    ) {
      millor = [esq, dre, regla];
    }
  }
  return millor;
}

function plega(s) {
  return s.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase();
}

function distancia(a, b) {
  const dp = Array.from({ length: a.length + 1 }, (_, i) => [i]);
  for (let j = 1; j <= b.length; j++) dp[0][j] = j;
  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      dp[i][j] = Math.min(
        dp[i - 1][j] + 1,
        dp[i][j - 1] + 1,
        dp[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1),
      );
    }
  }
  return dp[a.length][b.length];
}

// Semblança amb l'original: una paraula correcta gairebe sempre
// s'assembla al seu cognat en `es`/`en`; un enganxat, mai. Un afix
// pur (1-4 lletres de mes o de menys) no compta: es justament la
// forma de l'enganxat (`lapolitica` vs `politica`, `desdel` vs `desde`).
function esCognat(tok, refToks) {
  const base = tok.replace(/^[ldsmntc]['’]/, "");
  const t = plega(base);
  const llindar = t.length <= 4 ? 0 : 1;
  for (const r of refToks) {
    const rt = plega(r);
    if (Math.abs(rt.length - t.length) > Math.max(llindar, 4)) continue;
    const d = distancia(t, rt);
    if (d === 0) return true;
    if (d > llindar) continue;
    if (esAfix(t, rt)) continue;
    return true;
  }
  return false;
}

function esAfix(t, rt) {
  const dif = Math.abs(t.length - rt.length);
  if (dif < 1 || dif > 4) return false;
  return (
    t.startsWith(rt) || t.endsWith(rt) || rt.startsWith(t) || rt.endsWith(t)
  );
}

function refToks(textEn, textEs) {
  const toks = new Set();
  for (const t of tokenitza(textEn)) toks.add(t);
  for (const t of tokenitza(textEs)) toks.add(t);
  return toks;
}

function contextNet(frag) {
  return !/https?:|www\.|@|\|target:|\.mcp\.json/.test(frag);
}

function revisaPuntuacio(text) {
  const trobats = [];
  for (const m of text.matchAll(PUNT_RE)) {
    const punt = m[0][0];
    const seg = m[1];
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    if (!contextNet(frag)) continue;
    if (m[0] === "](") continue; // enllaç markdown
    if (m[0] === ":s" && frag.includes("|target:")) continue; // [x|target:self]
    if (/%[sd]\.\(/.test(frag)) continue; // notació tècnica %s.(sufix)...
    if (punt === "." && !/[A-ZÀÈÉÍÒÓÚÜ«"“(%$]/.test(seg)) continue;
    trobats.push({ que: m[0], frag });
  }
  for (const m of text.matchAll(CAMEL_RE)) {
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    if (MARQUES_OK.some((mk) => frag.includes(mk))) continue;
    trobats.push({ que: m[0], frag });
  }
  for (const m of text.matchAll(PH_GLUED_RE)) {
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    trobats.push({ que: m[0], frag });
  }
  for (const m of text.matchAll(/[a-zàèéíòóúüç·]d['’][0-9]/gu)) {
    const frag = text.slice(Math.max(0, m.index - 30), m.index + 32);
    trobats.push({ que: m[0], frag });
  }
  return trobats;
}

function carrega(ruta) {
  return fs.readFile(ruta).then((buf) => gt.po.parse(buf, "utf-8"));
}

async function revisa(locale) {
  const base = `./translations/${locale}.po`;
  const [dades, dadesEn, dadesEs] = await Promise.all([
    carrega(base),
    carrega("./translations/en.po"),
    carrega("./translations/es.po").catch(() => null),
  ]);
  const entrades = dades.translations[""];
  const entradesEn = dadesEn.translations[""];
  const entradesEs = dadesEs ? dadesEs.translations[""] : {};
  const errors = [];
  const avisos = [];

  const freq = new Map();
  for (const [msgid, e] of Object.entries(entrades)) {
    if (msgid === "") continue;
    for (const t of e.msgstr)
      for (const w of tokenitza(t)) {
        freq.set(w, (freq.get(w) ?? 0) + 1);
      }
  }

  for (const [msgid, e] of Object.entries(entrades)) {
    if (msgid === "") continue;
    const textos = e.msgstr;
    const eEn = entradesEn[msgid];
    const textosEn = eEn ? eEn.msgstr : [];
    const eEs = entradesEs[msgid];
    const textosEs = eEs ? eEs.msgstr : [];

    if (eEn?.msgid_plural && !e.msgid_plural) {
      errors.push(
        `${msgid}: l'original usa msgid_plural pero el ${locale} no te msgstr[0]/[1]`,
      );
    }

    textos.forEach((text, i) => {
      if (!text) return;
      const textEn = textosEn[i] ?? "";
      const textEs = textosEs[i] ?? "";
      const refs = refToks(textEn, textEs);
      if (textEn) {
        for (const re of PLACEHOLDER_RES) {
          const nEn = compte(textEn, re);
          const nCa = compte(text, re);
          if (nEn !== nCa) {
            errors.push(
              `${msgid}[${i}]: placeholders ${re.source}: en=${nEn} ${locale}=${nCa}`,
            );
          }
        }
      }
      for (const t of revisaPuntuacio(text)) {
        errors.push(
          `${msgid}: puntuacio enganxada ${JSON.stringify(t.que)} ...${t.frag}...`,
        );
      }
      for (const tok of tokenitza(text)) {
        if (SKIP_RE.test(tok)) continue;
        if (PARAULES_OK.has(tok)) continue;
        // Sense salt per frequencia del token sencer: un enganxat
        // repetit (`del'equip` x3) no s'ha d'auto-validar mai.
        const base = baseApostrof(tok);
        if (base && partValida(base, freq)) continue;
        if (esCognat(tok, refs)) continue;
        const part = millorParticio(tok, freq);
        if (!part) continue;
        const [esq, dre, regla] = part;
        const linia = `${msgid}: possible enganxat ${JSON.stringify(tok)} -> ${esq} + ${dre}`;
        if (regla === "contingut") avisos.push(`${linia} (revisar)`);
        else errors.push(linia);
      }
    });
  }
  return { errors, avisos };
}

const FIXTURES = [
  // [text, esperaError]
  ["Els membres del'equip continuaran.", true],
  ["Accepteu lapolítica de privadesa.", true],
  ["Si necessiteu més informació,contacteu amb nosaltres.", true],
  ["Revisions delPenpot disponibles.", true],
  ["Seleccioneu Lowercase oCapitalize.", true],
  ["Cobreix fins a %seditors nous.", true],
  ["Les biblioteques compartides.", false],
  ["Edita el webhook.", false],
  ["Emplenament del grup.", false],
  ["S'està desant el fitxer.", false],
  ["Els components no es poden niar.", false],
  ["Gira horitzontalment.", false],
  ["Desbloquegeu les funcions.", false],
  ["Atributs SVG importats.", false],
  ["Commuta la negreta.", false],
];

async function selfTest() {
  // Vocabulari minim per a les fixtures.
  const freq = new Map(
    "els membres continuaran accepteu de privadesa si necessiteu més informació amb nosaltres revisions disponibles seleccioneu lowercase infrequent les biblioteques compartides edita el webhook emplenament del grup està desant fitxer components no es poden niar gira commuta la negreta desbloquegeu les funcions atributs svg importats horitzontalment podeu crear equip política"
      .split(" ")
      .map((w) => [w, 3]),
  );
  let mal = 0;
  for (const [text, esperaError] of FIXTURES) {
    const trobats = [];
    for (const t of revisaPuntuacio(text)) trobats.push(`punt:${t.que}`);
    for (const tok of tokenitza(text)) {
      if (SKIP_RE.test(tok) || FUNCIO.has(tok)) continue;
      if (PARAULES_OK.has(tok)) continue;
      if (partValida(tok, freq)) continue;
      const base = baseApostrof(tok);
      if (base && partValida(base, freq)) continue;
      const part = millorParticio(tok, freq);
      if (part && part[2] !== "contingut") trobats.push(`tok:${tok}`);
    }
    const hiHaError = trobats.length > 0;
    if (hiHaError !== esperaError) {
      console.error(
        `SELF-TEST FALLA: ${JSON.stringify(text)} esperava error=${esperaError}, trobats=${JSON.stringify(trobats)}`,
      );
      mal++;
    }
  }
  if (mal > 0) process.exit(1);
  console.log(`SELF-TEST OK: ${FIXTURES.length} casos`);
}

const opcions = getopts(process.argv.slice(2), {
  string: ["l"],
  boolean: ["self-test", "h"],
  alias: { locale: ["l"], help: ["h"] },
});

if (opcions.h) {
  console.log(`QA de traduccions PO.
Us: node ./scripts/check-translations.js [-l <locale>] [--self-test]
  -l: locale a revisar (defecte: ca), des de frontend/
  --self-test: comprova el detector amb casos integrats`);
  process.exit(0);
}

if (opcions["self-test"]) {
  await selfTest();
} else {
  const locale = opcions.l ?? opcions.locale ?? "ca";
  let dades;
  try {
    dades = await revisa(locale);
  } catch (err) {
    console.error(
      `No s'ha pogut llegir translations/${locale}.po: ${err.message}`,
    );
    process.exit(2);
  }
  for (const w of dades.avisos) console.log(`avis: ${w}`);
  for (const e of dades.errors) console.error(`error: ${e}`);
  console.log(
    `${locale}: ${dades.errors.length} errors, ${dades.avisos.length} avisos`,
  );
  process.exit(dades.errors.length > 0 ? 1 : 0);
}
