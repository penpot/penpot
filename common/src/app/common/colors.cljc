;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.colors
  (:refer-clojure :exclude [test])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [cuerdas.core :as str]))

(def black "#000000")

(def default-layout "#DE4762")
(def gray-20 "#B1B2B5")
(def info "#59B9E2")
(def test "#fabada")
(def white "#FFFFFF")
(def warning "#FC8802")

;; new-css-system colors
(def new-primary "#7efff5")
(def new-danger "#ff3277")
(def new-warning "#fe4811")
(def new-primary-light "#6911d4")
(def background-quaternary "#2e3434")
(def background-quaternary-light "#eef0f2")
(def canvas "#E8E9EA")

(def names
  {"aliceblue" "#f0f8ff"
   "antiquewhite" "#faebd7"
   "aqua" "#00ffff"
   "aquamarine" "#7fffd4"
   "azure" "#f0ffff"
   "beige" "#f5f5dc"
   "bisque" "#ffe4c4"
   "black" "#000000"
   "blanchedalmond" "#ffebcd"
   "blue" "#0000ff"
   "blueviolet" "#8a2be2"
   "brown" "#a52a2a"
   "burlywood" "#deb887"
   "cadetblue" "#5f9ea0"
   "chartreuse" "#7fff00"
   "chocolate" "#d2691e"
   "coral" "#ff7f50"
   "cornflowerblue" "#6495ed"
   "cornsilk" "#fff8dc"
   "crimson" "#dc143c"
   "cyan" "#00ffff"
   "darkblue" "#00008b"
   "darkcyan" "#008b8b"
   "darkgoldenrod" "#b8860b"
   "darkgray" "#a9a9a9"
   "darkgreen" "#006400"
   "darkgrey" "#a9a9a9"
   "darkkhaki" "#bdb76b"
   "darkmagenta" "#8b008b"
   "darkolivegreen" "#556b2f"
   "darkorange" "#ff8c00"
   "darkorchid" "#9932cc"
   "darkred" "#8b0000"
   "darksalmon" "#e9967a"
   "darkseagreen" "#8fbc8f"
   "darkslateblue" "#483d8b"
   "darkslategray" "#2f4f4f"
   "darkslategrey" "#2f4f4f"
   "darkturquoise" "#00ced1"
   "darkviolet" "#9400d3"
   "deeppink" "#ff1493"
   "deepskyblue" "#00bfff"
   "dimgray" "#696969"
   "dimgrey" "#696969"
   "dodgerblue" "#1e90ff"
   "firebrick" "#b22222"
   "floralwhite" "#fffaf0"
   "forestgreen" "#228b22"
   "fuchsia" "#ff00ff"
   "gainsboro" "#dcdcdc"
   "ghostwhite" "#f8f8ff"
   "gold" "#ffd700"
   "goldenrod" "#daa520"
   "gray" "#808080"
   "green" "#008000"
   "greenyellow" "#adff2f"
   "grey" "#808080"
   "honeydew" "#f0fff0"
   "hotpink" "#ff69b4"
   "indianred" "#cd5c5c"
   "indigo" "#4b0082"
   "ivory" "#fffff0"
   "khaki" "#f0e68c"
   "lavender" "#e6e6fa"
   "lavenderblush" "#fff0f5"
   "lawngreen" "#7cfc00"
   "lemonchiffon" "#fffacd"
   "lightblue" "#add8e6"
   "lightcoral" "#f08080"
   "lightcyan" "#e0ffff"
   "lightgoldenrodyellow" "#fafad2"
   "lightgray" "#d3d3d3"
   "lightgreen" "#90ee90"
   "lightgrey" "#d3d3d3"
   "lightpink" "#ffb6c1"
   "lightsalmon" "#ffa07a"
   "lightseagreen" "#20b2aa"
   "lightskyblue" "#87cefa"
   "lightslategray" "#778899"
   "lightslategrey" "#778899"
   "lightsteelblue" "#b0c4de"
   "lightyellow" "#ffffe0"
   "lime" "#00ff00"
   "limegreen" "#32cd32"
   "linen" "#faf0e6"
   "magenta" "#ff00ff"
   "maroon" "#800000"
   "mediumaquamarine" "#66cdaa"
   "mediumblue" "#0000cd"
   "mediumorchid" "#ba55d3"
   "mediumpurple" "#9370db"
   "mediumseagreen" "#3cb371"
   "mediumslateblue" "#7b68ee"
   "mediumspringgreen" "#00fa9a"
   "mediumturquoise" "#48d1cc"
   "mediumvioletred" "#c71585"
   "midnightblue" "#191970"
   "mintcream" "#f5fffa"
   "mistyrose" "#ffe4e1"
   "moccasin" "#ffe4b5"
   "navajowhite" "#ffdead"
   "navy" "#000080"
   "oldlace" "#fdf5e6"
   "olive" "#808000"
   "olivedrab" "#6b8e23"
   "orange" "#ffa500"
   "orangered" "#ff4500"
   "orchid" "#da70d6"
   "palegoldenrod" "#eee8aa"
   "palegreen" "#98fb98"
   "paleturquoise" "#afeeee"
   "palevioletred" "#db7093"
   "papayawhip" "#ffefd5"
   "peachpuff" "#ffdab9"
   "peru" "#cd853f"
   "pink" "#ffc0cb"
   "plum" "#dda0dd"
   "powderblue" "#b0e0e6"
   "purple" "#800080"
   "red" "#ff0000"
   "rosybrown" "#bc8f8f"
   "royalblue" "#4169e1"
   "saddlebrown" "#8b4513"
   "salmon" "#fa8072"
   "sandybrown" "#f4a460"
   "seagreen" "#2e8b57"
   "seashell" "#fff5ee"
   "sienna" "#a0522d"
   "silver" "#c0c0c0"
   "skyblue" "#87ceeb"
   "slateblue" "#6a5acd"
   "slategray" "#708090"
   "slategrey" "#708090"
   "snow" "#fffafa"
   "springgreen" "#00ff7f"
   "steelblue" "#4682b4"
   "tan" "#d2b48c"
   "teal" "#008080"
   "thistle" "#d8bfd8"
   "tomato" "#ff6347"
   "turquoise" "#40e0d0"
   "violet" "#ee82ee"
   "wheat" "#f5deb3"
   "white" "#ffffff"
   "whitesmoke" "#f5f5f5"
   "yellow" "#ffff00"
   "yellowgreen" "#9acd32"})

(def ^:private hex-color-re
  #"\#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})")

(def ^:private rgb-color-re
  #"(?:|rgb)\((\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\)")

(defn valid-hex-color?
  [color]
  (and (string? color)
       (some? (re-matches hex-color-re color))))

(defn parse-rgb
  [color]
  (let [result (re-matches rgb-color-re color)]
    (when (some? result)
      (let [r (parse-long (nth result 1))
            g (parse-long (nth result 2))
            b (parse-long (nth result 3))]
        (when (and (<= 0 r 255) (<= 0 g 255) (<= 0 b 255))
          [r g b])))))

(defn valid-rgb-color?
  [color]
  (if (string? color)
    (let [result (parse-rgb color)]
      (some? result))
    false))

(defn- normalize-hex
  [color]
  (if (= (count color) 4)  ; of the form #RGB
    (-> color
        (str/replace #"\#(.)(.)(.)" "#$1$1$2$2$3$3")
        (str/lower))
    (str/lower color)))

(defn rgb->str
  [[r g b a]]
  (if (some? a)
    (str/ffmt "rgba(%,%,%,%)" r g b a)
    (str/ffmt "rgb(%,%,%)" r g b)))

(defn rgb->hsv
  [[red green blue]]
  (let [max (d/max red green blue)
        min (d/min red green blue)
        val max]
    (if (= min max)
      [0 0 val]
      (let [delta (- max min)
            sat   (/ delta max)
            hue   (if (= red max)
                    (/ (- green blue) delta)
                    (if (= green max)
                      (+ 2 (/ (- blue red) delta))
                      (+ 4 (/ (- red green) delta))))
            hue   (* 60 hue)
            hue   (if (< hue 0)
                    (+ hue 360)
                    hue)
            hue   (if (> hue 360)
                    (- hue 360)
                    hue)]
        [hue sat val]))))

(defn hsv->rgb
  [[h s brightness]]
  (if (= s 0)
    [brightness brightness brightness]
    (let [sextant    (int (mth/floor (/ h 60)))
          remainder  (- (/ h 60) sextant)
          brightness (d/nilv brightness 0)
          val1       (int (* brightness (- 1 s)))
          val2       (int (* brightness (- 1 (* s remainder))))
          val3       (int (* brightness (- 1 (* s (- 1 remainder)))))]
      (case sextant
        1 [val2 brightness val1]
        2 [val1 brightness val3]
        3 [val1 val2 brightness]
        4 [val3 val1 brightness]
        5 [brightness val1 val2]
        6 [brightness val3 val1]
        0 [brightness val3 val1]))))

(defn hex->rgb
  [color]
  (try
    (let [rgb #?(:clj (Integer/parseInt (subs color 1) 16)
                 :cljs (js/parseInt (subs color 1) 16))
          r   (bit-shift-right rgb 16)
          g   (bit-and (bit-shift-right rgb 8) 255)
          b   (bit-and rgb 255)]
      [r g b])
    (catch #?(:clj Throwable :cljs :default) _cause
      [0 0 0])))

(defn hex->lum
  [color]
  (let [[r g b] (hex->rgb color)]
    (mth/sqrt (+ (* 0.241 r)
                 (* 0.691 g)
                 (* 0.068 b)))))

(defn- int->hex
  "Convert integer to hex string"
  [v]
  #?(:clj  (Integer/toHexString v)
     :cljs (.toString v 16)))

(defn rgb->hex
  [[r g b]]
  (let [r (int r)
        g (int g)
        b (int b)]
    (if (or (not= r (bit-and r 255))
            (not= g (bit-and g 255))
            (not= b (bit-and b 255)))
      (throw (ex-info "not valid rgb" {:r r :g g :b b}))
      (let [rgb (bit-or (bit-shift-left r 16)
                        (bit-shift-left g 8) b)]
        (if (< r 16)
          (dm/str "#" (subs (int->hex (bit-or 0x1000000 rgb)) 1))
          (dm/str "#" (int->hex rgb)))))))

(defn rgb->hsl
  [[r g b]]
  (let [norm-r (/ r 255.0)
        norm-g (/ g 255.0)
        norm-b (/ b 255.0)
        max    (d/max norm-r norm-g norm-b)
        min    (d/min norm-r norm-g norm-b)
        l      (/ (+ max min) 2.0)
        h      (if (= max min) 0
                   (if (= max norm-r)
                     (* 60 (/ (- norm-g norm-b) (- max min)))
                     (if (= max norm-g)
                       (+ 120 (* 60 (/ (- norm-b norm-r) (- max min))))
                       (+ 240 (* 60 (/ (- norm-r norm-g) (- max min)))))))
        s      (if (and (> l 0) (<= l 0.5))
                 (/ (- max min) (* 2 l))
                 (/ (- max min) (- 2 (* 2 l))))]
    [(mod (+ h 360) 360) s l]))

(defn hex->hsv
  [v]
  (-> v hex->rgb rgb->hsv))

(defn hex->rgba
  [data opacity]
  (-> (hex->rgb data)
      (conj opacity)))

(defn hex->hsl [hex]
  (-> hex hex->rgb rgb->hsl))

(defn hex->hsla
  [data opacity]
  (-> (hex->hsl data)
      (conj opacity)))

(defn format-hsla
  [[h s l a]]
  (let [precision 2
        rounded-h (int h)
        rounded-s (d/format-number (* 100 s) precision)
        rounded-l (d/format-number (* 100 l) precision)
        rounded-a (d/format-number a precision)]
    (str/concat "" rounded-h " " rounded-s "% " rounded-l "% / " rounded-a)))

(defn format-rgba
  [[r g b a]]
  (let [precision 2
        rounded-a (d/format-number a precision)]
    (str/ffmt "%, %, %, %" r g b rounded-a)))

(defn- hue->rgb
  "Helper for hsl->rgb"
  [v1 v2 vh]
  (let [vh (if (< vh 0)
             (+ vh 1)
             (if (> vh 1)
               (- vh 1)
               vh))]
    (cond
      (< (* 6 vh) 1) (+ v1 (* (- v2 v1) 6 vh))
      (< (* 2 vh) 1) v2
      (< (* 3 vh) 2) (+ v1 (* (- v2 v1) (- (/ 2 3) vh) 6))
      :else v1)))

(defn hsl->rgb
  [[h s l]]
  (if (= s 0)
    (let [o (* l 255)]
      [o o o])
    (let [norm-h (/ h 360.0)
          temp2  (if (< l 0.5)
                   (* l (+ 1 s))
                   (- (+ l s)
                      (* s l)))
          temp1  (- (* l 2) temp2)]

      [(mth/round (* 255 (hue->rgb temp1 temp2 (+ norm-h (/ 1 3)))))
       (mth/round (* 255 (hue->rgb temp1 temp2 norm-h)))
       (mth/round (* 255 (hue->rgb temp1 temp2 (- norm-h (/ 1 3)))))])))

(defn hsl->hex
  [v]
  (-> v hsl->rgb rgb->hex))

(defn hsl->hsv
  [hsl]
  (-> hsl hsl->rgb rgb->hsv))

(defn hsv->hex
  [hsv]
  (-> hsv hsv->rgb rgb->hex))

(defn hsv->hsl
  [hsv]
  (-> hsv hsv->hex hex->hsl))

(defn expand-hex
  [v]
  (cond
    (re-matches #"^[0-9A-Fa-f]$" v)
    (dm/str v v v v v v)

    (re-matches #"^[0-9A-Fa-f]{2}$" v)
    (dm/str v v v)

    (re-matches #"^[0-9A-Fa-f]{3}$" v)
    (let [a (nth v 0)
          b (nth v 1)
          c (nth v 2)]
      (dm/str a a b b c c))

    :else
    v))

(defn prepend-hash
  [color]
  (if (= "#" (subs color 0 1))
    color
    (dm/str "#" color)))

(defn remove-hash
  [color]
  (if (str/starts-with? color "#")
    (subs color 1)
    color))

(defn color-string?
  [color]
  (and (string? color)
       (or (valid-hex-color? color)
           (valid-rgb-color? color)
           (contains? names color))))

(defn parse
  [color]
  (when (string? color)
    (if (or (valid-hex-color? color)
            (valid-hex-color? (dm/str "#" color)))
      (normalize-hex color)
      (or (some-> (parse-rgb color) (rgb->hex))
          (get names (str/lower color))))))

(def color-names
  (into [] (keys names)))

(def empty-color
  (into {} (map #(vector % nil)) [:color :id :file-id :gradient :opacity]))

(defn next-rgb
  "Given a color in rgb returns the next color"
  [[r g b]]
  (cond
    (and (= 255 r) (= 255 g) (= 255 b))
    (throw (ex-info "cannot get next color" {:r r :g g :b b}))

    (and (= 255 g) (= 255 b))
    [(inc r) 0 0]

    (= 255 b)
    [r (inc g) 0]

    :else
    [r g (inc b)]))

(defn reduce-range
  [value range]
  (/ (mth/floor (* value range)) range))

(defn sort-colors
  [a b]
  (let [[ah _ av] (hex->hsv (:color a))
        [bh _ bv] (hex->hsv (:color b))
        ah (reduce-range (/ ah 60) 8)
        bh (reduce-range (/ bh 60) 8)
        av (/ av 255)
        bv (/ bv 255)
        a (+ (* ah 100) (* av 10))
        b (+ (* bh 100) (* bv 10))]
    (compare a b)))

;; --- CSS color parsing
;;
;; A port of the color string parsing of tinycolor2, the library that
;; Style Dictionary uses, so design token colors resolve to the same
;; color and opacity in every runtime.

(def ^:private css-names
  (assoc names
         "burntsienna" "#ea7e5d"
         "rebeccapurple" "#663399"))

(def ^:private css-unit
  "(?:[-\\+]?\\d*\\.\\d+%?)|(?:[-\\+]?\\d+%?)")

(def ^:private css-unit-re
  (re-pattern css-unit))

(defn- css-color-re
  [prefix n]
  (re-pattern
   (str prefix
        "[\\s|\\(]+(" css-unit ")"
        (apply str (repeat (dec n) (str "[,|\\s]+(" css-unit ")")))
        "\\s*\\)?")))

(def ^:private css-color-matchers
  [[:rgb  (css-color-re "rgb" 3)]
   [:rgba (css-color-re "rgba" 4)]
   [:hsl  (css-color-re "hsl" 3)]
   [:hsla (css-color-re "hsla" 4)]
   [:hsv  (css-color-re "hsv" 3)]
   [:hsva (css-color-re "hsva" 4)]])

(def ^:private hex8-re #"^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$")
(def ^:private hex6-re #"^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$")
(def ^:private hex4-re #"^#?([0-9a-f])([0-9a-f])([0-9a-f])([0-9a-f])$")
(def ^:private hex3-re #"^#?([0-9a-f])([0-9a-f])([0-9a-f])$")

(defn- parse-float
  "Number at the start of `s`, like JavaScript's `parseFloat`, or NaN."
  [s]
  (if (number? s)
    s
    (if-let [match (re-find #"^[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" s)]
      #?(:clj (Double/parseDouble match)
         :cljs (js/parseFloat match))
      ##NaN)))

(defn- parse-number
  "`s` as a number when it is all a number, like JavaScript's `Number`, or
  NaN."
  [s]
  (if (number? s)
    s
    (if (re-matches #"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?" s)
      (parse-float s)
      ##NaN)))

(defn- round
  [n]
  #?(:clj (Math/round (double n))
     :cljs (js/Math.round n)))

(defn- nan?
  [n]
  #?(:clj (Double/isNaN (double n))
     :cljs (js/isNaN n)))

(defn- hex->int
  [s]
  #?(:clj (Integer/parseInt s 16)
     :cljs (js/parseInt s 16)))

(defn- bound01
  "`n`, a number or a CSS unit string in [0, `max`], as a number in [0, 1]."
  [n max]
  (let [n        (if (and (string? n)
                          (str/includes? n ".")
                          (== 1 (parse-float n)))
                   "100%"
                   n)
        percent? (and (string? n) (str/includes? n "%"))
        n        (min max (clojure.core/max 0 (parse-float n)))
        n        (if percent?
                   (/ (mth/floor (* n max)) 100)
                   n)]
    (if (< (mth/abs (- n max)) 0.000001)
      1
      (/ (double (mod n max)) max))))

(defn- number->str
  "`n` printed as JavaScript prints numbers."
  [n]
  (if (== n (mth/floor n))
    (str (long n))
    (str n)))

(defn- percentage
  "`n` as a percentage string when it is a fraction of one."
  [n]
  (let [value (parse-number n)]
    (if (and (not (nan? value)) (<= value 1))
      (str (number->str (* value 100)) "%")
      n)))

(defn- bound-alpha
  [a]
  (let [a (parse-float a)]
    (if (or (nan? a) (< a 0) (> a 1)) 1 a)))

(defn- hue->rgb*
  [p q t]
  (let [t (cond (< t 0) (+ t 1)
                (> t 1) (- t 1)
                :else   t)]
    (cond
      (< t (/ 1 6)) (+ p (* (- q p) 6 t))
      (< t (/ 1 2)) q
      (< t (/ 2 3)) (+ p (* (- q p) (- (/ 2 3) t) 6))
      :else         p)))

(defn- css-hsl->rgb
  [h s l]
  (let [h (bound01 h 360)
        s (bound01 s 100)
        l (bound01 l 100)]
    (if (zero? s)
      [(* l 255) (* l 255) (* l 255)]
      (let [q (if (< l 0.5) (* l (+ 1 s)) (- (+ l s) (* l s)))
            p (- (* 2 l) q)]
        [(* 255 (hue->rgb* p q (+ h (/ 1 3))))
         (* 255 (hue->rgb* p q h))
         (* 255 (hue->rgb* p q (- h (/ 1 3))))]))))

(defn- css-hsv->rgb
  [h s v]
  (let [h   (* 6 (bound01 h 360))
        s   (bound01 s 100)
        v   (bound01 v 100)
        i   (mth/floor h)
        f   (- h i)
        p   (* v (- 1 s))
        q   (* v (- 1 (* f s)))
        t   (* v (- 1 (* (- 1 f) s)))
        idx (int (mod i 6))]
    [(* 255 (nth [v q p p t v] idx))
     (* 255 (nth [t v v q p p] idx))
     (* 255 (nth [p p t v v q] idx))]))

(defn- css-string->color
  "Parse `s` into a map with the color components, in the form tinycolor2
  reads them, or nil."
  [s]
  (let [s      (-> s str/trim str/lower)
        named  (get css-names s)
        s      (or named s)
        format (when named :name)]
    (if (= s "transparent")
      {:r 0 :g 0 :b 0 :a 0 :format :name}
      (or (some (fn [[type re]]
                  (when-let [[_ c1 c2 c3 c4] (re-find re s)]
                    (case type
                      :rgb  {:r c1 :g c2 :b c3}
                      :rgba {:r c1 :g c2 :b c3 :a c4}
                      :hsl  {:h c1 :s c2 :l c3}
                      :hsla {:h c1 :s c2 :l c3 :a c4}
                      :hsv  {:h c1 :s c2 :v c3}
                      :hsva {:h c1 :s c2 :v c3 :a c4})))
                css-color-matchers)
          (when-let [[_ r g b a] (re-find hex8-re s)]
            {:r (hex->int r) :g (hex->int g) :b (hex->int b)
             :a (/ (double (hex->int a)) 255) :format (or format :hex8)})
          (when-let [[_ r g b] (re-find hex6-re s)]
            {:r (hex->int r) :g (hex->int g) :b (hex->int b)
             :format (or format :hex)})
          (when-let [[_ r g b a] (re-find hex4-re s)]
            {:r (hex->int (str r r)) :g (hex->int (str g g)) :b (hex->int (str b b))
             :a (/ (double (hex->int (str a a))) 255) :format (or format :hex8)})
          (when-let [[_ r g b] (re-find hex3-re s)]
            {:r (hex->int (str r r)) :g (hex->int (str g g)) :b (hex->int (str b b))
             :format (or format :hex)})))))

(defn- css-unit?
  [v]
  (and (some? v) (some? (re-find css-unit-re (str v)))))

(defn- css-color->rgba
  "Components of the parsed color `c` as `[r g b a format]`, or nil when it
  isn't a valid color."
  [{:keys [r g b h s l v a format] :as c}]
  (let [[rgb format]
        (cond
          (and (css-unit? r) (css-unit? g) (css-unit? b))
          [[(* 255 (bound01 r 255)) (* 255 (bound01 g 255)) (* 255 (bound01 b 255))]
           (or format (if (str/ends-with? (str r) "%") :prgb :rgb))]

          (and (css-unit? h) (css-unit? s) (css-unit? v))
          [(css-hsv->rgb h (percentage s) (percentage v)) (or format :hsv)]

          (and (css-unit? h) (css-unit? s) (css-unit? l))
          [(css-hsl->rgb h (percentage s) (percentage l)) (or format :hsl)])]
    (when (some? rgb)
      (let [clamp #(let [c (min 255 (max % 0))] (if (< c 1) (round c) c))]
        (conj (mapv clamp rgb)
              (bound-alpha (if (contains? c :a) a 1))
              format)))))

(defn parse-css-color
  "Parse a CSS color string into `{:color hex :opacity alpha}`, with the
  same rules as tinycolor2. Hex colors need the `#` prefix. Nil when
  `color` isn't a valid CSS color."
  [color]
  (when (string? color)
    (when-let [[r g b a format] (some-> (css-string->color color) (css-color->rgba))]
      (when (or (not (contains? #{:hex :hex8} format))
                (str/starts-with? color "#"))
        {:color (str "#" (str/join (map #(let [h (str/lower (int->hex (int (round %))))]
                                           (if (= 1 (count h)) (str "0" h) h))
                                        [r g b])))
         :opacity a}))))
