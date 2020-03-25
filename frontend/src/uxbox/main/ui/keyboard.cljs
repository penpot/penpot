(ns uxbox.main.ui.keyboard)

(defn is-keycode?
  [keycode]
  (fn [e]
    (= (.-keyCode e) keycode)))

(defn ^boolean ctrl?
  [event]
  (.-ctrlKey event))

(defn ^boolean shift?
  [event]
  (.-shiftKey event))

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))
(def space? (is-keycode? 32))
