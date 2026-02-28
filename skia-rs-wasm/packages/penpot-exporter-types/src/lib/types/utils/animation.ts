export type Animation = AnimationDissolve | AnimationSlide | AnimationPush;

type AnimationDissolve = {
  animationType: 'dissolve';
  duration: number;
  easing: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
};

type AnimationSlide = {
  animationType: 'slide';
  duration: number;
  easing: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
  way: 'in' | 'out';
  direction: 'right' | 'left' | 'up' | 'down';
  offsetEffect: boolean;
};

type AnimationPush = {
  animationType: 'push';
  duration: number;
  easing: 'linear' | 'ease' | 'ease-in' | 'ease-out' | 'ease-in-out';
  direction: 'right' | 'left' | 'up' | 'down';
};
