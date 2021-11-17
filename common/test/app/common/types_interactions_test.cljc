;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types-interactions-test
  (:require
   [clojure.test :as t]
   [clojure.pprint :refer [pprint]]
   [app.common.exceptions :as ex]
   [app.common.pages.init :as cpi]
   [app.common.types.interactions :as cti]
   [app.common.uuid :as uuid]
   [app.common.geom.point :as gpt]))

(t/deftest set-event-type
  (let [interaction cti/default-interaction
        shape       (cpi/make-minimal-shape :rect)
        frame       (cpi/make-minimal-shape :frame)]

    (t/testing "Set event type unchanged"
      (let [new-interaction
            (cti/set-event-type interaction :click shape)]
        (t/is (= :click (:event-type new-interaction)))))

    (t/testing "Set event type changed"
      (let [new-interaction
            (cti/set-event-type interaction :mouse-press shape)]
          (t/is (= :mouse-press (:event-type new-interaction)))))

    (t/testing "Set after delay on non-frame"
      (let [result (ex/try
                     (cti/set-event-type interaction :after-delay shape))]
        (t/is (ex/exception? result))))

    (t/testing "Set after delay on frame"
      (let [new-interaction
            (cti/set-event-type interaction :after-delay frame)]
        (t/is (= :after-delay (:event-type new-interaction)))
        (t/is (= 600 (:delay new-interaction)))))

    (t/testing "Set after delay with previous data"
      (let [interaction (assoc interaction :delay 300)
            new-interaction
            (cti/set-event-type interaction :after-delay frame)]
        (t/is (= :after-delay (:event-type new-interaction)))
        (t/is (= 300 (:delay new-interaction)))))))


(t/deftest set-action-type
  (let [interaction cti/default-interaction]

    (t/testing "Set action type unchanged"
      (let [new-interaction
            (cti/set-action-type interaction :navigate)]
        (t/is (= :navigate (:action-type new-interaction)))))

    (t/testing "Set action type changed"
      (let [new-interaction
            (cti/set-action-type interaction :prev-screen)]
        (t/is (= :prev-screen (:action-type new-interaction)))))

    (t/testing "Set action type navigate"
      (let [interaction {:event-type :click
                         :action-type :prev-screen}
            new-interaction
            (cti/set-action-type interaction :navigate)]
        (t/is (= :navigate (:action-type new-interaction)))
        (t/is (nil? (:destination new-interaction)))
        (t/is (= false (:preserve-scroll new-interaction)))))

    (t/testing "Set action type navigate with previous data"
      (let [destination (uuid/next)
            interaction {:event-type      :click
                         :action-type     :prev-screen
                         :destination     destination
                         :preserve-scroll true}
            new-interaction
            (cti/set-action-type interaction :navigate)]
        (t/is (= :navigate (:action-type new-interaction)))
        (t/is (= destination (:destination new-interaction)))
        (t/is (= true (:preserve-scroll new-interaction)))))

    (t/testing "Set action type open-overlay"
      (let [new-interaction
            (cti/set-action-type interaction :open-overlay)]
        (t/is (= :open-overlay (:action-type new-interaction)))
        (t/is (= :center (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

    (t/testing "Set action type open-overlay with previous data"
      (let [interaction (assoc interaction :overlay-pos-type :top-left
                                           :overlay-position (gpt/point 100 200))
            new-interaction
            (cti/set-action-type interaction :open-overlay)]
        (t/is (= :open-overlay (:action-type new-interaction)))
        (t/is (= :top-left (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 100 200) (:overlay-position new-interaction)))))

    (t/testing "Set action type toggle-overlay"
      (let [new-interaction
            (cti/set-action-type interaction :toggle-overlay)]
        (t/is (= :toggle-overlay (:action-type new-interaction)))
        (t/is (= :center (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

    (t/testing "Set action type toggle-overlay with previous data"
      (let [interaction (assoc interaction :overlay-pos-type :top-left
                                           :overlay-position (gpt/point 100 200))
            new-interaction
            (cti/set-action-type interaction :toggle-overlay)]
        (t/is (= :toggle-overlay (:action-type new-interaction)))
        (t/is (= :top-left (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 100 200) (:overlay-position new-interaction)))))

    (t/testing "Set action type close-overlay"
      (let [new-interaction
            (cti/set-action-type interaction :close-overlay)]
        (t/is (= :close-overlay (:action-type new-interaction)))
        (t/is (nil? (:destination new-interaction)))))

    (t/testing "Set action type close-overlay with previous data"
      (let [destination (uuid/next)
            interaction (assoc interaction :destination destination)
            new-interaction
            (cti/set-action-type interaction :close-overlay)]
        (t/is (= :close-overlay (:action-type new-interaction)))
        (t/is (= destination (:destination new-interaction)))))

    (t/testing "Set action type prev-screen"
      (let [new-interaction
            (cti/set-action-type interaction :prev-screen)]
        (t/is (= :prev-screen (:action-type new-interaction)))))

    (t/testing "Set action type open-url"
      (let [new-interaction
            (cti/set-action-type interaction :open-url)]
        (t/is (= :open-url (:action-type new-interaction)))
        (t/is (= "" (:url new-interaction)))))

    (t/testing "Set action type open-url with previous data"
      (let [interaction (assoc interaction :url "https://example.com")
            new-interaction
            (cti/set-action-type interaction :open-url)]
        (t/is (= :open-url (:action-type new-interaction)))
        (t/is (= "https://example.com" (:url new-interaction)))))))


(t/deftest option-delay
  (let [frame (cpi/make-minimal-shape :frame)
        i1    cti/default-interaction
        i2    (cti/set-event-type i1 :after-delay frame)]

  (t/testing "Has delay"
    (t/is (not (cti/has-delay i1)))
    (t/is (cti/has-delay i2)))

  (t/testing "Set delay"
    (let [new-interaction (cti/set-delay i2 1000)]
      (t/is (= 1000 (:delay new-interaction)))))))


(t/deftest option-destination
  (let [destination (uuid/next)
        i1          cti/default-interaction
        i2          (cti/set-action-type i1 :prev-screen)
        i3          (cti/set-action-type i1 :open-overlay)]

  (t/testing "Has destination"
    (t/is (cti/has-destination i1))
    (t/is (not (cti/has-destination i2))))

  (t/testing "Set destination"
    (let [new-interaction (cti/set-destination i1 destination)]
      (t/is (= destination (:destination new-interaction)))
      (t/is (nil? (:overlay-pos-type new-interaction)))
      (t/is (nil? (:overlay-position new-interaction)))))

  (t/testing "Set destination of overlay"
    (let [new-interaction (cti/set-destination i3 destination)]
      (t/is (= destination (:destination new-interaction)))
      (t/is (= :center (:overlay-pos-type new-interaction)))
      (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))))


(t/deftest option-preserve-scroll
  (let [i1 cti/default-interaction
        i2 (cti/set-action-type i1 :prev-screen)]

  (t/testing "Has preserve-scroll"
    (t/is (cti/has-preserve-scroll i1))
    (t/is (not (cti/has-preserve-scroll i2))))

  (t/testing "Set preserve-scroll"
    (let [new-interaction (cti/set-preserve-scroll i1 true)]
      (t/is (= true (:preserve-scroll new-interaction)))))))


(t/deftest option-url
  (let [i1 cti/default-interaction
        i2 (cti/set-action-type i1 :open-url)]

  (t/testing "Has url"
    (t/is (not (cti/has-url i1)))
    (t/is (cti/has-url i2)))

  (t/testing "Set url"
    (let [new-interaction (cti/set-url i2 "https://example.com")]
      (t/is (= "https://example.com" (:url new-interaction)))))))


(t/deftest option-overlay-opts
  (let [base-frame    (-> (cpi/make-minimal-shape :frame)
                          (assoc-in [:selrect :width] 100)
                          (assoc-in [:selrect :height] 100))
        overlay-frame (-> (cpi/make-minimal-shape :frame)
                          (assoc-in [:selrect :width] 30)
                          (assoc-in [:selrect :height] 20))
        objects       {(:id base-frame) base-frame
                       (:id overlay-frame) overlay-frame}

        i1 cti/default-interaction
        i2 (cti/set-action-type i1 :open-overlay)
        i3 (-> i1
               (cti/set-action-type :open-overlay)
               (cti/set-destination (:id overlay-frame)))]

  (t/testing "Has overlay options"
    (t/is (not (cti/has-overlay-opts i1)))
    (t/is (cti/has-overlay-opts i2)))

  (t/testing "Set overlay-pos-type without destination"
    (let [new-interaction (cti/set-overlay-pos-type i2 :top-right base-frame objects)]
      (t/is (= :top-right (:overlay-pos-type new-interaction)))
      (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

  (t/testing "Set overlay-pos-type with destination and auto"
    (let [new-interaction (cti/set-overlay-pos-type i3 :bottom-right base-frame objects)]
      (t/is (= :bottom-right (:overlay-pos-type new-interaction)))
      (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

  (t/testing "Set overlay-pos-type with destination and manual"
    (let [new-interaction (cti/set-overlay-pos-type i3 :manual base-frame objects)]
      (t/is (= :manual (:overlay-pos-type new-interaction)))
      (t/is (= (gpt/point 35 40) (:overlay-position new-interaction)))))

  (t/testing "Toggle overlay-pos-type"
    (let [new-interaction (cti/toggle-overlay-pos-type i3 :center base-frame objects)
          new-interaction-2 (cti/toggle-overlay-pos-type new-interaction :center base-frame objects)
          new-interaction-3 (cti/toggle-overlay-pos-type new-interaction-2 :top-right base-frame objects)]
      (t/is (= :manual (:overlay-pos-type new-interaction)))
      (t/is (= (gpt/point 35 40) (:overlay-position new-interaction)))
      (t/is (= :center (:overlay-pos-type new-interaction-2)))
      (t/is (= (gpt/point 0 0) (:overlay-position new-interaction-2)))
      (t/is (= :top-right (:overlay-pos-type new-interaction-3)))
      (t/is (= (gpt/point 0 0) (:overlay-position new-interaction-3)))))

  (t/testing "Set overlay-position"
    (let [new-interaction (cti/set-overlay-position i3 (gpt/point 50 60))]
      (t/is (= :manual (:overlay-pos-type new-interaction)))
      (t/is (= (gpt/point 50 60) (:overlay-position new-interaction)))))

  (t/testing "Set close-click-outside"
    (let [new-interaction (cti/set-close-click-outside i3 true)]
      (t/is (not (:close-click-outside i3)))
      (t/is (:close-click-outside new-interaction))))

  (t/testing "Set background-overlay"
    (let [new-interaction (cti/set-background-overlay i3 true)]
      (t/is (not (:background-overlay i3)))
      (t/is (:background-overlay new-interaction))))

  ))


(t/deftest interactions
  (let [i1 (cti/set-action-type cti/default-interaction :open-overlay)
        i2 (cti/set-action-type cti/default-interaction :close-overlay)
        i3 (cti/set-action-type cti/default-interaction :prev-screen)
        interactions [i1 i2]]

    (t/testing "Add interaction to nil"
      (let [new-interactions (cti/add-interaction nil i3)]
        (t/is (= (count new-interactions) 1))
        (t/is (= (:action-type (last new-interactions)) :prev-screen))))

    (t/testing "Add interaction to normal"
      (let [new-interactions (cti/add-interaction interactions i3)]
        (t/is (= (count new-interactions) 3))
        (t/is (= (:action-type (last new-interactions)) :prev-screen))))

    (t/testing "Remove interaction"
      (let [new-interactions (cti/remove-interaction interactions 0)]
        (t/is (= (count new-interactions) 1))
        (t/is (= (:action-type (last new-interactions)) :close-overlay))))

    (t/testing "Update interaction"
      (let [new-interactions (cti/update-interaction interactions 1 #(cti/set-action-type % :open-url))]
        (t/is (= (count new-interactions) 2))
        (t/is (= (:action-type (last new-interactions)) :open-url))))

    ))

