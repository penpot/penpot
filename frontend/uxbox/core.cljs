(ns uxbox.core
  (:require [uxbox.state]
            [uxbox.ui :as ui]
            [uxbox.ui.navigation]
            [uxbox.router]
            [uxbox.rstore]
            [goog.dom :as dom]))

(enable-console-print!)

(let [dom (dom/getElement "app")]
  (ui/mount! dom))

