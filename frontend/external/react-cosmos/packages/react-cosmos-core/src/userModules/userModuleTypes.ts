import { ComponentType, FunctionComponent, ReactNode } from 'react';

type FixtureMap<FixtureType> = { [fixtureName: string]: FixtureType };
type FixtureExport<FixtureType> = FixtureType | FixtureMap<FixtureType>;
type FixtureModule<FixtureType> = { default: FixtureExport<FixtureType> };

type ModuleWrapper<ModuleType> = { module: ModuleType };
type LazyModuleWrapper<ModuleType> = { getModule: () => Promise<ModuleType> };

export type ReactFixture = ReactNode | FunctionComponent;
export type ReactFixtureMap = FixtureMap<ReactFixture>;
export type ReactFixtureExport = FixtureExport<ReactFixture>;
export type ReactFixtureModule = FixtureModule<ReactFixture>;
export type ReactFixtureWrapper = ModuleWrapper<ReactFixtureModule>;
export type LazyReactFixtureWrapper = LazyModuleWrapper<ReactFixtureModule>;

export type ReactDecoratorProps = { children: ReactNode };
export type ReactDecorator = ComponentType<ReactDecoratorProps>;
export type ReactDecoratorModule = { default: ReactDecorator };
export type ReactDecoratorWrapper = ModuleWrapper<ReactDecoratorModule>;
export type LazyReactDecoratorWrapper = LazyModuleWrapper<ReactDecoratorModule>;

export type ByPath<T> = Record<string, T>;

export type UserModuleWrappers =
  | {
      lazy: true;
      fixtures: ByPath<LazyReactFixtureWrapper>;
      decorators: ByPath<LazyReactDecoratorWrapper>;
    }
  | {
      lazy: false;
      fixtures: ByPath<ReactFixtureWrapper>;
      decorators: ByPath<ReactDecoratorWrapper>;
    };

export type FixtureModules = {
  fixtureModule: ReactFixtureModule;
  decoratorModules: ReactDecoratorModule[];
};
