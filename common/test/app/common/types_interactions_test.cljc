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
      (t/is (:background-overlay new-interaction))))))


(t/deftest animation-checks
  (let [i1 cti/default-interaction
        i2 (cti/set-action-type i1 :open-overlay)
        i3 (cti/set-action-type i1 :toggle-overlay)
        i4 (cti/set-action-type i1 :close-overlay)
        i5 (cti/set-action-type i1 :prev-screen)
        i6 (cti/set-action-type i1 :open-url)]

    (t/testing "Has animation?"
      (t/is (cti/has-animation? i1))
      (t/is (cti/has-animation? i2))
      (t/is (cti/has-animation? i3))
      (t/is (cti/has-animation? i4))
      (t/is (not (cti/has-animation? i5)))
      (t/is (not (cti/has-animation? i6))))

    (t/testing "Valid push?"
      (t/is (cti/allow-push? (:action-type i1)))
      (t/is (not (cti/allow-push? (:action-type i2))))
      (t/is (not (cti/allow-push? (:action-type i3))))
      (t/is (not (cti/allow-push? (:action-type i4))))
      (t/is (not (cti/allow-push? (:action-type i5))))
      (t/is (not (cti/allow-push? (:action-type i6)))))))


(t/deftest set-animation-type
  (let [i1 cti/default-interaction
        i2 (cti/set-animation-type i1 :dissolve)]

    (t/testing "Set animation type nil"
      (let [new-interaction
            (cti/set-animation-type i1 nil)]
        (t/is (nil? (-> new-interaction :animation :animation-type)))))

    (t/testing "Set animation type unchanged"
      (let [new-interaction
            (cti/set-animation-type i2 :dissolve)]
        (t/is (= :dissolve (-> new-interaction :animation :animation-type)))))

    (t/testing "Set animation type changed"
      (let [new-interaction
            (cti/set-animation-type i2 :slide)]
        (t/is (= :slide (-> new-interaction :animation :animation-type)))))

    (t/testing "Set animation type reset"
      (let [new-interaction
            (cti/set-animation-type i2 nil)]
        (t/is (nil? (-> new-interaction :animation)))))

    (t/testing "Set animation type dissolve"
      (let [new-interaction
            (cti/set-animation-type i1 :dissolve)]
        (t/is (= :dissolve (-> new-interaction :animation :animation-type)))
        (t/is (= 300 (-> new-interaction :animation :duration)))
        (t/is (= :linear (-> new-interaction :animation :easing)))))

    (t/testing "Set animation type dissolve with previous data"
      (let [interaction (assoc i1 :animation {:animation-type :slide
                                              :duration 1000
                                              :easing :ease-out
                                              :way :out
                                              :direction :left
                                              :offset-effect true})
            new-interaction
            (cti/set-animation-type interaction :dissolve)]
        (t/is (= :dissolve (-> new-interaction :animation :animation-type)))
        (t/is (= 1000 (-> new-interaction :animation :duration)))
        (t/is (= :ease-out (-> new-interaction :animation :easing)))))

    (t/testing "Set animation type slide"
      (let [new-interaction
            (cti/set-animation-type i1 :slide)]
        (t/is (= :slide (-> new-interaction :animation :animation-type)))
        (t/is (= 300 (-> new-interaction :animation :duration)))
        (t/is (= :linear (-> new-interaction :animation :easing)))
        (t/is (= :in (-> new-interaction :animation :way)))
        (t/is (= :right (-> new-interaction :animation :direction)))
        (t/is (= false (-> new-interaction :animation :offset-effect)))))

    (t/testing "Set animation type slide with previous data"
      (let [interaction (assoc i1 :animation {:animation-type :dissolve
                                              :duration 1000
                                              :easing :ease-out
                                              :way :out
                                              :direction :left
                                              :offset-effect true})
            new-interaction
            (cti/set-animation-type interaction :slide)]
        (t/is (= :slide (-> new-interaction :animation :animation-type)))
        (t/is (= 1000 (-> new-interaction :animation :duration)))
        (t/is (= :ease-out (-> new-interaction :animation :easing)))
        (t/is (= :out (-> new-interaction :animation :way)))
        (t/is (= :left (-> new-interaction :animation :direction)))
        (t/is (= true (-> new-interaction :animation :offset-effect)))))

    (t/testing "Set animation type push"
      (let [new-interaction
            (cti/set-animation-type i1 :push)]
        (t/is (= :push (-> new-interaction :animation :animation-type)))
        (t/is (= 300 (-> new-interaction :animation :duration)))
        (t/is (= :linear (-> new-interaction :animation :easing)))
        (t/is (= :right (-> new-interaction :animation :direction)))))

    (t/testing "Set animation type push with previous data"
      (let [interaction (assoc i1 :animation {:animation-type :slide
                                              :duration 1000
                                              :easing :ease-out
                                              :way :out
                                              :direction :left
                                              :offset-effect true})
            new-interaction
            (cti/set-animation-type interaction :push)]
        (t/is (= :push (-> new-interaction :animation :animation-type)))
        (t/is (= 1000 (-> new-interaction :animation :duration)))
        (t/is (= :ease-out (-> new-interaction :animation :easing)))
        (t/is (= :left (-> new-interaction :animation :direction)))))))


(t/deftest allowed-animation
  (let [i1 (cti/set-action-type cti/default-interaction :open-overlay)
        i2 (cti/set-action-type cti/default-interaction :close-overlay)
        i3 (cti/set-action-type cti/default-interaction :toggle-overlay)]

    (t/testing "Cannot use animation push for an overlay action"
      (let [bad-interaction-1 (assoc i1 :animation {:animation-type :push
                                                    :duration 1000
                                                    :easing :ease-out
                                                    :direction :left})
            bad-interaction-2 (assoc i2 :animation {:animation-type :push
                                                    :duration 1000
                                                    :easing :ease-out
                                                    :direction :left})
            bad-interaction-3 (assoc i3 :animation {:animation-type :push
                                                    :duration 1000
                                                    :easing :ease-out
                                                    :direction :left})]
        (t/is (not (cti/allowed-animation? (:action-type bad-interaction-1)
                                           (-> bad-interaction-1 :animation :animation-type))))
        (t/is (not (cti/allowed-animation? (:action-type bad-interaction-2)
                                           (-> bad-interaction-1 :animation :animation-type))))
        (t/is (not (cti/allowed-animation? (:action-type bad-interaction-3)
                                           (-> bad-interaction-1 :animation :animation-type))))))

    (t/testing "Remove animation if moving to an forbidden state"
      (let [interaction (cti/set-animation-type cti/default-interaction :push)
            new-interaction (cti/set-action-type interaction :open-overlay)]
        (t/is (nil? (:animation new-interaction)))))))


(t/deftest option-duration
  (let [i1 cti/default-interaction
        i2 (cti/set-animation-type cti/default-interaction :dissolve)]

  (t/testing "Has duration?"
    (t/is (not (cti/has-duration? i1)))
    (t/is (cti/has-duration? i2)))

  (t/testing "Set duration"
    (let [new-interaction (cti/set-duration i2 1000)]
      (t/is (= 1000 (-> new-interaction :animation :duration)))))))


(t/deftest option-easing
  (let [i1 cti/default-interaction
        i2 (cti/set-animation-type cti/default-interaction :dissolve)]

  (t/testing "Has easing?"
    (t/is (not (cti/has-easing? i1)))
    (t/is (cti/has-easing? i2)))

  (t/testing "Set easing"
    (let [new-interaction (cti/set-easing i2 :ease-in)]
      (t/is (= :ease-in (-> new-interaction :animation :easing)))))))


(t/deftest option-way
  (let [i1 cti/default-interaction
        i2 (cti/set-animation-type cti/default-interaction :slide)
        i3 (cti/set-action-type i2 :open-overlay)]

  (t/testing "Has way?"
    (t/is (not (cti/has-way? i1)))
    (t/is (cti/has-way? i2))
    (t/is (not (cti/has-way? i3)))
    (t/is (some? (-> i3 :animation :way)))) ; <- it exists but is ignored

  (t/testing "Set way"
    (let [new-interaction (cti/set-way i2 :out)]
      (t/is (= :out (-> new-interaction :animation :way)))))))


(t/deftest option-direction
  (let [i1 cti/default-interaction
        i2 (cti/set-animation-type cti/default-interaction :push)
        i3 (cti/set-animation-type cti/default-interaction :dissolve)]

  (t/testing "Has direction?"
    (t/is (not (cti/has-direction? i1)))
    (t/is (cti/has-direction? i2)))

  (t/testing "Set direction"
    (let [new-interaction (cti/set-direction i2 :left)]
      (t/is (= :left (-> new-interaction :animation :direction)))))

  (t/testing "Invert direction"
    (let [a-none (:animation i3)
          a-right (:animation i2)
          a-left (assoc a-right :direction :left)
          a-up (assoc a-right :direction :up)
          a-down (assoc a-right :direction :down)

          a-nil' (cti/invert-direction nil)
          a-none' (cti/invert-direction a-none)
          a-right' (cti/invert-direction a-right)
          a-left' (cti/invert-direction a-left)
          a-up' (cti/invert-direction a-up)
          a-down' (cti/invert-direction a-down)]

      (t/is (nil? a-nil'))
      (t/is (nil? (:direction a-none')))
      (t/is (= :left (:direction a-right')))
      (t/is (= :right (:direction a-left')))
      (t/is (= :down (:direction a-up')))
      (t/is (= :up (:direction a-down')))))))


(t/deftest option-offset-effect
  (let [i1 cti/default-interaction
        i2 (cti/set-animation-type cti/default-interaction :slide)
        i3 (cti/set-action-type i2 :open-overlay)]

  (t/testing "Has offset-effect"
    (t/is (not (cti/has-offset-effect? i1)))
    (t/is (cti/has-offset-effect? i2))
    (t/is (not (cti/has-offset-effect? i3)))
    (t/is (some? (-> i3 :animation :offset-effect)))) ; <- it exists but is ignored

  (t/testing "Set offset-effect"
    (let [new-interaction (cti/set-offset-effect i2 true)]
      (t/is (= true (-> new-interaction :animation :offset-effect)))))))


(t/deftest modify-interactions
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
        (t/is (= (:action-type (last new-interactions)) :open-url))))))


(t/deftest remap-interactions
  (let [frame1 (cpi/make-minimal-shape :frame)
        frame2 (cpi/make-minimal-shape :frame)
        frame3 (cpi/make-minimal-shape :frame)
        frame4 (cpi/make-minimal-shape :frame)
        frame5 (cpi/make-minimal-shape :frame)
        frame6 (cpi/make-minimal-shape :frame)

        objects {(:id frame3) frame3
                 (:id frame4) frame4
                 (:id frame5) frame5}

        ids-map {(:id frame1) (:id frame4)
                 (:id frame2) (:id frame5)}

        i1 (cti/set-destination cti/default-interaction (:id frame1))
        i2 (cti/set-destination cti/default-interaction (:id frame2))
        i3 (cti/set-destination cti/default-interaction (:id frame3))
        i4 (cti/set-destination cti/default-interaction nil)
        i5 (cti/set-destination cti/default-interaction (:id frame6))

        interactions [i1 i2 i3 i4 i5]]

    (t/testing "Remap interactions"
      (let [new-interactions (cti/remap-interactions interactions ids-map objects)]
        (t/is (= (count new-interactions) 4))
        (t/is (= (:id frame4) (:destination (get new-interactions 0))))
        (t/is (= (:id frame5) (:destination (get new-interactions 1))))
        (t/is (= (:id frame3) (:destination (get new-interactions 2))))
        (t/is (nil? (:destination (get new-interactions 3))))))))

