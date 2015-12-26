(ns uxbox.library.icons)

(def ^:private +material+
  [{:name "Attachment"
    :id :material/attachment
    :type :builtin/icon
    :view-box [0 0 50 50]
    :data [:path {:d "M15 36c-6.08 0-11-4.93-11-11s4.92-11 11-11h21c4.42 0 8 3.58 8 8s-3.58 8-8 8h-17c-2.76 0-5-2.24-5-5s2.24-5 5-5h15v3h-15c-1.1 0-2 .89-2 2s.9 2 2 2h17c2.76 0 5-2.24 5-5s-2.24-5-5-5h-21c-4.42 0-8 3.58-8 8s3.58 8 8 8h19v3h-19z"}]}
   {:name "Cloud"
    :id :material/cloud
    :type :builtin/icon
    :view-box [0 0 50 50]
    :data [:path {:d "M38.71 20.07c-1.36-6.88-7.43-12.07-14.71-12.07-5.78 0-10.79 3.28-13.3 8.07-6.01.65-10.7 5.74-10.7 11.93 0 6.63 5.37 12 12 12h26c5.52 0 10-4.48 10-10 0-5.28-4.11-9.56-9.29-9.93z"}]}
   ])

(def ^:private +external+
  [{:name "Custon icon"
    :view-box [0 0 50 50]
    :id :material/foobar
    :type :builtin/icon-svg
    :image {:xlink-href "http://s.cdpn.io/3/kiwi.svg"
            :width 50
            :height 50}}])

(def +collections+
  [{:name "Material design"
    :id 1
    :icons +material+}
   {:name "External icons"
    :id 2
    :icons +external+}])
