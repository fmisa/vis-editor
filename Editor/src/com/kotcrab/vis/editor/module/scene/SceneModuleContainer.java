/*
 * Copyright 2014-2015 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kotcrab.vis.editor.module.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.utils.Array;
import com.kotcrab.vis.editor.Assets;
import com.kotcrab.vis.editor.Log;
import com.kotcrab.vis.editor.entity.EntityScheme;
import com.kotcrab.vis.editor.module.Module;
import com.kotcrab.vis.editor.module.ModuleContainer;
import com.kotcrab.vis.editor.module.ModuleInput;
import com.kotcrab.vis.editor.module.editor.EditorModuleContainer;
import com.kotcrab.vis.editor.module.project.*;
import com.kotcrab.vis.editor.scene.EditorScene;
import com.kotcrab.vis.editor.ui.scene.SceneTab;
import com.kotcrab.vis.runtime.scene.SceneViewport;
import com.kotcrab.vis.runtime.system.CameraManager;
import com.kotcrab.vis.runtime.system.ParticleRenderSystem;
import com.kotcrab.vis.runtime.system.RenderBatchingSystem;
import com.kotcrab.vis.runtime.util.ArtemisUtils;
import com.kotcrab.vis.runtime.util.EntityEngine;
import com.kotcrab.vis.runtime.util.EntityEngineConfiguration;

/**
 * Module container for scene scope modules.
 * @author Kotcrab
 */
public class SceneModuleContainer extends ModuleContainer<SceneModule> implements ModuleInput {
	private Project project;
	private EditorModuleContainer editorModuleContainer;
	private ProjectModuleContainer projectModuleContainer;

	private EditorScene scene;
	private SceneTab sceneTab;

	private EntityEngine engine;
	private EntityEngineConfiguration config;

	public SceneModuleContainer (ProjectModuleContainer projectModuleContainer, SceneTab sceneTab, EditorScene scene, Batch batch) {
		this.editorModuleContainer = projectModuleContainer.getEditorContainer();
		this.projectModuleContainer = projectModuleContainer;
		this.scene = scene;
		this.sceneTab = sceneTab;

		config = new EntityEngineConfiguration();

		config.setManager(new CameraManager(SceneViewport.SCREEN, 0, 0, scene.pixelsPerUnit)); //size ignored for screen viewport
		config.setManager(new LayerManipulatorManager());
		config.setManager(new ZIndexManipulatorManager());
		config.setManager(new EntitySerializerManager());
		config.setManager(new TextureReloaderManager(projectModuleContainer.get(TextureCacheModule.class)));
		config.setManager(new ParticleReloaderManager(projectModuleContainer.get(ParticleCacheModule.class), scene.pixelsPerUnit));
		config.setManager(new FontReloaderManager(projectModuleContainer.get(FontCacheModule.class), scene.pixelsPerUnit));
		config.setManager(new VisUUIDManager());

		config.setSystem(new GroupIdProviderSystem(), true);
		config.setSystem(new GroupProxyProviderSystem(), true);
		config.setSystem(new GridRendererSystem(batch, this));

		createEssentialsSystems(config, scene.pixelsPerUnit);

		ArtemisUtils.createCommonSystems(config, batch, Assets.distanceFieldShader, false);
		RenderBatchingSystem renderBatchingSystem = config.getSystem(RenderBatchingSystem.class);
		config.setSystem(new ParticleRenderSystem(renderBatchingSystem, true), true);
		config.setSystem(new SoundAndMusicRenderSystem(renderBatchingSystem, scene.pixelsPerUnit), true);

	}

	public static void createEssentialsSystems (EntityEngineConfiguration config, float pixelsPerUnit) {
		config.setManager(new EntityProxyCache(pixelsPerUnit));
		config.setSystem(new AssetsUsageAnalyzerSystem(), true);
	}

	public static void populateEngine (final EntityEngine engine, EditorScene scene) {
		Array<EntityScheme> schemes = scene.getSchemes();
		schemes.forEach(entityScheme -> entityScheme.build(engine));
	}

	@Override
	public void add (SceneModule module) {
		module.setProject(projectModuleContainer.getProject());
		module.setProjectModuleContainer(projectModuleContainer);
		module.setContainer(editorModuleContainer);
		module.setSceneObjects(this, sceneTab, scene);

		if (module instanceof EntityEngineConfigurator)
			((EntityEngineConfigurator) module).setupEntityEngine(engine);

		super.add(module);
	}

	@Override
	public void init () {
		super.init();
		engine = new EntityEngine(config);

		modules.forEach(sceneModule -> sceneModule.setEntityEngine(engine));

		Log.debug("SceneModuleContainer", "Populating EntityEngine");
		populateEngine(engine, scene);

		engine.getSystems().forEach(this::injectModules);
		engine.getManagers().forEach(this::injectModules);
	}

	@Override
	public <C extends Module> C findInHierarchy (Class<C> moduleClass) {
		C module = getOrNull(moduleClass);
		if (module != null) return module;

		return projectModuleContainer.findInHierarchy(moduleClass);
	}

	public EntityEngineConfiguration getEntityEngineConfiguration () {
		return config;
	}

	public EditorScene getScene () {
		return scene;
	}

	public Project getProject () {
		return project;
	}

	public void setProject (Project project) {
		if (getModuleCounter() > 0)
			throw new IllegalStateException("Project can't be changed while modules are loaded!");

		this.project = project;
	}

	@Override
	public void resize () {
		super.resize();
		engine.getManager(CameraManager.class).resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
	}

	public void render (Batch batch) {
		engine.setDelta(Gdx.graphics.getDeltaTime());
		engine.process();

		for (int i = 0; i < modules.size; i++)
			modules.get(i).render(batch);
	}

	public void onShow () {
		for (int i = 0; i < modules.size; i++)
			modules.get(i).onShow();
	}

	public void onHide () {
		for (int i = 0; i < modules.size; i++)
			modules.get(i).onHide();
	}

	public void save () {
		for (int i = 0; i < modules.size; i++)
			modules.get(i).save();
	}

	public EntityEngine getEntityEngine () {
		if (engine == null)
			throw new IllegalStateException("SceneModuleContainer wasn't initialized yet, use #getEntityEngineConfiguration if you need to get engine system or manager!");

		return engine;
	}

	@Override
	public boolean touchDown (InputEvent event, float x, float y, int pointer, int button) {
		boolean returnValue = false;

		for (int i = 0; i < modules.size; i++)
			if (modules.get(i).touchDown(event, x, y, pointer, button)) returnValue = true;

		return returnValue;
	}

	@Override
	public void touchUp (InputEvent event, float x, float y, int pointer, int button) {
		for (int i = 0; i < modules.size; i++)
			modules.get(i).touchUp(event, x, y, pointer, button);
	}

	@Override
	public void touchDragged (InputEvent event, float x, float y, int pointer) {
		for (int i = 0; i < modules.size; i++)
			modules.get(i).touchDragged(event, x, y, pointer);
	}

	@Override
	public boolean mouseMoved (InputEvent event, float x, float y) {
		boolean returnValue = false;

		for (int i = 0; i < modules.size; i++)
			if (modules.get(i).mouseMoved(event, x, y)) returnValue = true;

		return returnValue;
	}

	@Override
	public void enter (InputEvent event, float x, float y, int pointer, Actor fromActor) {
		for (int i = 0; i < modules.size; i++)
			modules.get(i).enter(event, x, y, pointer, fromActor);
	}

	@Override
	public void exit (InputEvent event, float x, float y, int pointer, Actor toActor) {
		for (int i = 0; i < modules.size; i++)
			modules.get(i).exit(event, x, y, pointer, toActor);
	}

	@Override
	public boolean scrolled (InputEvent event, float x, float y, int amount) {
		boolean returnValue = false;

		for (int i = 0; i < modules.size; i++)
			if (modules.get(i).scrolled(event, x, y, amount)) returnValue = true;

		return returnValue;
	}

	@Override
	public boolean keyDown (InputEvent event, int keycode) {
		boolean returnValue = false;

		for (int i = 0; i < modules.size; i++)
			if (modules.get(i).keyDown(event, keycode)) returnValue = true;

		return returnValue;
	}

	@Override
	public boolean keyUp (InputEvent event, int keycode) {
		boolean returnValue = false;

		for (int i = 0; i < modules.size; i++)
			if (modules.get(i).keyUp(event, keycode)) returnValue = true;

		return returnValue;
	}

	@Override
	public boolean keyTyped (InputEvent event, char character) {
		boolean returnValue = false;

		for (int i = 0; i < modules.size; i++)
			if (modules.get(i).keyTyped(event, character)) returnValue = true;

		return returnValue;
	}
}
