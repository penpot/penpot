(ns app.main.ui.keyboard)

(defn is-keycode?
  [keycode]
  (fn [e]
    (= (.-keyCode e) keycode)))

(defn ^boolean alt?
  [event]
  (.-altKey event))

(defn ^boolean ctrl?
  [event]
  (.-ctrlKey event))

(defn ^boolean meta?
  [event]
  (.-metaKey event))

(defn ^boolean shift?
  [event]
  (.-shiftKey event))

(def esc? (is-keycode? 27))
(def enter? (is-keycode? 13))
(def space? (is-keycode? 32))
(def up-arrow? (is-keycode? 38))
(def down-arrow? (is-keycode? 40))
