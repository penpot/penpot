(ns uxbox.ui.keyboard)

(defn is-keycode?
  [keycode]
  (fn [e]
    (= (.-keyCode e) keycode)))

(defn ctrl?
  [event]
  (.-ctrlKey event))

(defn shift?
  [event]
  (.-shiftKey event))

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))
