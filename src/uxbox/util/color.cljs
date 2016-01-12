(ns uxbox.util.color
  "Color conversion utils.")

(defn hex->rgb
  [^string data]
  (some->> (re-find #"^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$" data)
           (rest)
           (mapv #(js/parseInt % 16))))

(defn rgb->hex
  [[r g b]]
  (letfn [(to-hex [c]
            (let [hexdata (.toString c 16)]
              (if (= (count hexdata) 1)
                (str "0" hexdata)
                hexdata)))]
    (str "#" (to-hex r) (to-hex g) (to-hex b))))
