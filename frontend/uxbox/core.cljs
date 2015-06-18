(ns uxbox.core
  (:require [uxbox.ui :as ui]
            [uxbox.ui.navigation]
            [uxbox.state]
            [uxbox.rstore]
            [goog.dom :as dom]))

(enable-console-print!)

(let [dom (dom/getElement "app")]
  (ui/mount! dom))

