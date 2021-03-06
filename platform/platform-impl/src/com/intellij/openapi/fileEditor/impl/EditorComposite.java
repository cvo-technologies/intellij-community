/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.openapi.fileEditor.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.NullUtils;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.FocusWatcher;
import com.intellij.ui.JBColor;
import com.intellij.ui.PrevNextActionsDescriptor;
import com.intellij.ui.SideBorder;
import com.intellij.ui.TabbedPaneWrapper;
import com.intellij.ui.tabs.UiDecorator;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * This class hides internal structure of UI component which represent
 * set of opened editors. For example, one myEditor is represented by its
 * component, more then one myEditor is wrapped into tabbed pane.
 *
 * @author Vladimir Kondratyev
 */
public abstract class EditorComposite implements Disposable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.fileEditor.impl.EditorComposite");

  /**
   * File for which composite is created
   */
  @NotNull private final VirtualFile myFile;
  /**
   * Whether the composite is pinned or not
   */
  private boolean myPinned;
  /**
   * Editors which are opened in the composite
   */
  protected FileEditor[] myEditors;
  /**
   * This is initial timestamp of the file. It uses to implement
   * "close non modified editors first" feature.
   */
  private final long myInitialFileTimeStamp;
  protected TabbedPaneWrapper myTabbedPaneWrapper;
  private final MyComponent myComponent;
  private final FocusWatcher myFocusWatcher;
  /**
   * Currently selected myEditor
   */
  private FileEditor mySelectedEditor;
  private final FileEditorManagerEx myFileEditorManager;
  private final Map<FileEditor, JComponent> myTopComponents = new HashMap<FileEditor, JComponent>();
  private final Map<FileEditor, JComponent> myBottomComponents = new HashMap<FileEditor, JComponent>();
  private final Map<FileEditor, String> myDisplayNames = ContainerUtil.newHashMap();

  /**
   * @param file <code>file</code> for which composite is being constructed
   *
   * @param editors <code>edittors</code> that should be placed into the composite
   *
   * @exception java.lang.IllegalArgumentException if <code>editors</code>
   * is <code>null</code> or <code>providers</code> is <code>null</code> or <code>myEditor</code> arrays is empty
   */
  EditorComposite(@NotNull final VirtualFile file,
                  @NotNull final FileEditor[] editors,
                  @NotNull final FileEditorManagerEx fileEditorManager) {
    myFile = file;
    myEditors = editors;
    if (NullUtils.hasNull(editors)) throw new IllegalArgumentException("Must not pass null editors in " + Arrays.asList(editors));
    myFileEditorManager = fileEditorManager;
    myInitialFileTimeStamp     = myFile.getTimeStamp();

    Disposer.register(fileEditorManager.getProject(), this);

    if(editors.length > 1){
      myTabbedPaneWrapper = createTabbedPaneWrapper(editors);
      JComponent component = myTabbedPaneWrapper.getComponent();
      myComponent = new MyComponent(component, component);
    }
    else if(editors.length==1){
      myTabbedPaneWrapper=null;
      FileEditor editor = editors[0];
      myComponent = new MyComponent(createEditorComponent(editor), editor.getPreferredFocusedComponent());
    }
    else{
      throw new IllegalArgumentException("editors array cannot be empty");
    }

    mySelectedEditor = editors[0];
    myFocusWatcher = new FocusWatcher();
    myFocusWatcher.install(myComponent);

    myFileEditorManager.addFileEditorManagerListener(new FileEditorManagerAdapter() {
      @Override
      public void selectionChanged(@NotNull final FileEditorManagerEvent event) {
        final VirtualFile oldFile = event.getOldFile();
        final VirtualFile newFile = event.getNewFile();
        if (Comparing.equal(oldFile, newFile) && Comparing.equal(getFile(), newFile)) {
          Runnable runnable = new Runnable() {
            @Override
            public void run() {
              final FileEditor oldEditor = event.getOldEditor();
              if (oldEditor != null) oldEditor.deselectNotify();
              final FileEditor newEditor = event.getNewEditor();
              if (newEditor != null) newEditor.selectNotify();
              ((FileEditorProviderManagerImpl)FileEditorProviderManager.getInstance()).providerSelected(EditorComposite.this);
              ((IdeDocumentHistoryImpl)IdeDocumentHistory.getInstance(myFileEditorManager.getProject())).onSelectionChanged();
            }
          };
          if (ApplicationManager.getApplication().isDispatchThread()) {
            CommandProcessor.getInstance().executeCommand(myFileEditorManager.getProject(), runnable, "Switch Active Editor", null);
          }
          else {
            runnable.run(); // not invoked by user
          }
        }
      }
    }, this);
  }

  @NotNull
  private TabbedPaneWrapper.AsJBTabs createTabbedPaneWrapper(FileEditor[] editors) {
    PrevNextActionsDescriptor descriptor = new PrevNextActionsDescriptor(IdeActions.ACTION_NEXT_EDITOR_TAB, IdeActions.ACTION_PREVIOUS_EDITOR_TAB);
    final TabbedPaneWrapper.AsJBTabs wrapper = new TabbedPaneWrapper.AsJBTabs(myFileEditorManager.getProject(), SwingConstants.BOTTOM, descriptor, this);
    wrapper.getTabs().getPresentation().setPaintBorder(0, 0, 0, 0).setTabSidePaintBorder(1).setGhostsAlwaysVisible(true).setUiDecorator(new UiDecorator() {
      @Override
      @NotNull
      public UiDecoration getDecoration() {
        return new UiDecoration(null, new Insets(0, 8, 0, 8));
      }
    });
    wrapper.getTabs().getComponent().setBorder(new EmptyBorder(0, 0, 1, 0));

    boolean firstEditor = true;
    for (FileEditor editor : editors) {
      JComponent component = firstEditor && myComponent != null ? (JComponent)myComponent.getComponent(0) : createEditorComponent(editor);
      wrapper.addTab(getDisplayName(editor), component);
      firstEditor = false;
    }
    wrapper.addChangeListener(new MyChangeListener());

    return wrapper;
  }

  private JComponent createEditorComponent(final FileEditor editor) {
    JPanel component = new JPanel(new BorderLayout());
    JComponent comp = editor.getComponent();
    if (!FileEditorManagerImpl.isDumbAware(editor)) {
      comp = DumbService.getInstance(myFileEditorManager.getProject()).wrapGently(comp, editor);
    }

    component.add(comp, BorderLayout.CENTER);

    JPanel topPanel = new TopBottomPanel();
    myTopComponents.put(editor, topPanel);
    component.add(topPanel, BorderLayout.NORTH);

    final JPanel bottomPanel = new TopBottomPanel();
    myBottomComponents.put(editor, bottomPanel);
    component.add(bottomPanel, BorderLayout.SOUTH);

    return component;
  }

  /**
   * @return whether myEditor composite is pinned
   */
  public boolean isPinned(){
    return myPinned;
  }

  /**
   * Sets new "pinned" state
   */
  void setPinned(final boolean pinned){
    myPinned = pinned;
  }

  private void fireSelectedEditorChanged(final FileEditor oldSelectedEditor, final FileEditor newSelectedEditor){
    if ((!EventQueue.isDispatchThread() || !myFileEditorManager.isInsideChange()) && !Comparing.equal(oldSelectedEditor, newSelectedEditor)) {
      myFileEditorManager.notifyPublisher(new Runnable() {
        @Override
        public void run() {
          final FileEditorManagerEvent event = new FileEditorManagerEvent(myFileEditorManager, myFile, oldSelectedEditor, myFile, newSelectedEditor);
          final FileEditorManagerListener publisher = myFileEditorManager.getProject().getMessageBus().syncPublisher(FileEditorManagerListener.FILE_EDITOR_MANAGER);
          publisher.selectionChanged(event);
        }
      });
      final JComponent component = newSelectedEditor.getComponent();
      final EditorWindowHolder holder = UIUtil.getParentOfType(EditorWindowHolder.class, component);
      if (holder != null) {
        ((FileEditorManagerImpl)myFileEditorManager).addSelectionRecord(myFile, holder.getEditorWindow());
      }
    }
  }


  /**
   * @return preferred focused component inside myEditor composite. Composite uses FocusWatcher to
   * track focus movement inside the myEditor.
   */
  @Nullable
  public JComponent getPreferredFocusedComponent(){
    if (mySelectedEditor == null) return null;

    final Component component = myFocusWatcher.getFocusedComponent();
    if(!(component instanceof JComponent) || !component.isShowing() || !component.isEnabled() || !component.isFocusable()){
      return getSelectedEditor().getPreferredFocusedComponent();
    }
    return (JComponent)component;
  }

  /**
   * @return file for which composite was created.
   */
  @NotNull
  public VirtualFile getFile() {
    return myFile;
  }

  public FileEditorManager getFileEditorManager() {
    return myFileEditorManager;
  }

  /**
   * @return initial time stamp of the file (on moment of creation of
   * the composite)
   */
  public long getInitialFileTimeStamp() {
    return myInitialFileTimeStamp;
  }

  /**
   * @return editors which are opened in the composite. <b>Do not modify
   * this array</b>.
   */
  @NotNull
  public FileEditor[] getEditors() {
    return myEditors;
  }

  @NotNull
  public List<JComponent> getTopComponents(@NotNull FileEditor editor) {
    return getTopBottomComponents(editor, true);
  }

  @NotNull
  public List<JComponent> getBottomComponents(@NotNull FileEditor editor) {
    return getTopBottomComponents(editor, false);
  }

  @NotNull
  private List<JComponent> getTopBottomComponents(@NotNull FileEditor editor, boolean top) {
    SmartList<JComponent> result = new SmartList<JComponent>();
    JComponent container = top ? myTopComponents.get(editor) : myBottomComponents.get(editor);
    for (Component each : container.getComponents()) {
      if (each instanceof TopBottomComponentWrapper) {
        result.add(((TopBottomComponentWrapper)each).getWrappee());
      }
    }
    return Collections.unmodifiableList(result);
  }

  public void addTopComponent(FileEditor editor, JComponent component) {
    manageTopOrBottomComponent(editor, component, true, false);
  }

  public void removeTopComponent(FileEditor editor, JComponent component) {
    manageTopOrBottomComponent(editor, component, true, true);
  }

  public void addBottomComponent(FileEditor editor, JComponent component) {
    manageTopOrBottomComponent(editor, component, false, false);
  }

  public void removeBottomComponent(FileEditor editor, JComponent component) {
    manageTopOrBottomComponent(editor, component, false, true);
  }

  private void manageTopOrBottomComponent(FileEditor editor, JComponent component, boolean top, boolean remove) {
    final JComponent container = top ? myTopComponents.get(editor) : myBottomComponents.get(editor);
    assert container != null;

    if (remove) {
      container.remove(component.getParent());
    } else {
      container.add(new TopBottomComponentWrapper(component, top));
    }
    container.revalidate();
  }

  public void setDisplayName(@NotNull FileEditor editor, @NotNull String name) {
    int index = ContainerUtil.indexOfIdentity(ContainerUtil.immutableList(myEditors), editor);
    assert index != -1;

    myDisplayNames.put(editor, name);
    if (myTabbedPaneWrapper != null) {
      myTabbedPaneWrapper.setTitleAt(index, name);
    }
  }

  @NotNull
  protected String getDisplayName(@NotNull FileEditor editor) {
    return ObjectUtils.notNull(myDisplayNames.get(editor), editor.getName());
  }

  /**
   * @return currently selected myEditor.
   */
  @NotNull
  FileEditor getSelectedEditor() {
    return getSelectedEditorWithProvider().getFirst ();
  }

  public boolean isDisposed() {
    return myTabbedPaneWrapper != null && myTabbedPaneWrapper.isDisposed();
  }

  /**
   * @return currently selected myEditor with its provider.
   */
  @NotNull
  public abstract Pair<FileEditor, FileEditorProvider> getSelectedEditorWithProvider();

  void setSelectedEditor(final int index){
    if(myEditors.length == 1){
      // nothing to do
      LOG.assertTrue(myTabbedPaneWrapper == null);
    }
    else{
      LOG.assertTrue(myTabbedPaneWrapper != null);
      myTabbedPaneWrapper.setSelectedIndex(index);
    }
  }

  /**
   * @return component which represents set of file editors in the UI
   */
  public JComponent getComponent() {
    return myComponent;
  }

  /**
   * @return <code>true</code> if the composite contains at least one
   * modified myEditor
   */
  public boolean isModified(){
    for(int i=myEditors.length-1;i>=0;i--){
      if(myEditors[i].isModified()){
        return true;
      }
    }
    return false;
  }

  /**
   * Handles changes of selected myEditor
   */
  private final class MyChangeListener implements ChangeListener{
    @Override
    public void stateChanged(ChangeEvent e) {
      FileEditor oldSelectedEditor = mySelectedEditor;
      LOG.assertTrue(oldSelectedEditor != null);
      int selectedIndex = myTabbedPaneWrapper.getSelectedIndex();
      LOG.assertTrue(selectedIndex != -1);
      mySelectedEditor = myEditors[selectedIndex];
      fireSelectedEditorChanged(oldSelectedEditor, mySelectedEditor);
    }
  }

  private class MyComponent extends JPanel implements DataProvider{
    @Nullable
    private JComponent myFocusComponent;

    public MyComponent(@NotNull JComponent realComponent, @Nullable JComponent focusComponent){
      super(new BorderLayout());
      myFocusComponent = focusComponent;
      add(realComponent, BorderLayout.CENTER);
    }

    void setComponent(JComponent newComponent) {
      add(newComponent, BorderLayout.CENTER);
      myFocusComponent = newComponent;
    }

    @Override
    public boolean requestFocusInWindow() {
      return myFocusComponent == null ? false : myFocusComponent.requestFocusInWindow();
    }

    @Override
    public void requestFocus() {
      if (myFocusComponent != null) {
        myFocusComponent.requestFocus();
      }
    }

    @Override
    public boolean requestDefaultFocus() {
      return myFocusComponent == null ? false : myFocusComponent.requestDefaultFocus();
    }

    @Override
    public final Object getData(String dataId){
      if (PlatformDataKeys.FILE_EDITOR.is(dataId)) {
        return getSelectedEditor();
      }
      else if(CommonDataKeys.VIRTUAL_FILE.is(dataId)){
        return myFile.isValid() ? myFile : null;
      }
      else if(CommonDataKeys.VIRTUAL_FILE_ARRAY.is(dataId)){
        return myFile.isValid() ? new VirtualFile[] {myFile} : null;
      }
      else{
        JComponent component = getPreferredFocusedComponent();
        if(component instanceof DataProvider && component != this){
          return ((DataProvider)component).getData(dataId);
        }
        else{
          return null;
        }
      }
    }
  }

  @Override
  public void dispose() {
    for (FileEditor editor : myEditors) {
      if (!Disposer.isDisposed(editor)) {
        Disposer.dispose(editor);
      }
    }
    myFocusWatcher.deinstall(myFocusWatcher.getTopComponent());
  }

  void addEditor(@NotNull FileEditor editor) {
    ApplicationManager.getApplication().assertIsDispatchThread();
    myEditors = ArrayUtil.append(myEditors, editor);
    if (myTabbedPaneWrapper == null) {
      myTabbedPaneWrapper = createTabbedPaneWrapper(myEditors);
      myComponent.setComponent(myTabbedPaneWrapper.getComponent());
    }
    else {
      JComponent component = createEditorComponent(editor);
      myTabbedPaneWrapper.addTab(getDisplayName(editor), component);
    }
    myFocusWatcher.deinstall(myFocusWatcher.getTopComponent());
    myFocusWatcher.install(myComponent);
  }

  private static class TopBottomPanel extends JPanel {
    private TopBottomPanel() {
      setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    @Override
    public Color getBackground() {
      Color color = EditorColorsManager.getInstance().getGlobalScheme().getColor(EditorColors.GUTTER_BACKGROUND);
      return color == null ? EditorColors.GUTTER_BACKGROUND.getDefaultColor() : color;
    }
  }

  private static class TopBottomComponentWrapper extends JPanel {
    private final JComponent myWrappee;

    public TopBottomComponentWrapper(JComponent component, boolean top) {
      super(new BorderLayout());
      myWrappee = component;
      setOpaque(false);

      setBorder(new SideBorder(null, top ? SideBorder.BOTTOM : SideBorder.TOP) {
        @Override
        public Color getLineColor() {
          Color result = EditorColorsManager.getInstance().getGlobalScheme().getColor(EditorColors.TEARLINE_COLOR);
          return result == null ? JBColor.BLACK : result;
        }
      });

      add(component);
    }

    @NotNull
    public JComponent getWrappee() {
      return myWrappee;
    }
  }
}
