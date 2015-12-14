(ns uxbox.core
  (:require [uxbox.state]
            [uxbox.ui :as ui]
            [uxbox.router]
            [uxbox.rstore :as rs]
            [uxbox.data.projects :as dp]
            [goog.dom :as dom]))

(enable-console-print!)

(let [dom (dom/getElement "app")]
  (ui/mount! dom))

(defonce +setup-stuff+
  (do
    (rs/emit! (dp/create-project {:name "foo"
                                  :width 600
                                  :height 600
                                  :layout :mobile}))
    (rs/emit! (dp/create-project {:name "bar"
                                  :width 600
                                  :height 600
                                  :layout :mobile}))
    (rs/emit! (dp/create-project {:name "baz"
                                  :width 600
                                  :height 600
                                  :layout :mobile}))
    nil))

