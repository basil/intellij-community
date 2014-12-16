package com.intellij.openapi.vcs.changes.actions.migrate;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.DiffManager;
import com.intellij.openapi.diff.DiffNavigationContext;
import com.intellij.openapi.diff.DiffTool;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.util.diff.actions.impl.GoToChangePopupBuilder;
import com.intellij.openapi.util.diff.chains.DiffRequestChain;
import com.intellij.openapi.util.diff.chains.DiffRequestPresentable;
import com.intellij.openapi.util.diff.chains.DiffRequestPresentableException;
import com.intellij.openapi.util.diff.chains.SimpleDiffRequestChain;
import com.intellij.openapi.util.diff.contents.BinaryFileContentImpl;
import com.intellij.openapi.util.diff.contents.DiffContent;
import com.intellij.openapi.util.diff.contents.DocumentContentImpl;
import com.intellij.openapi.util.diff.contents.EmptyContent;
import com.intellij.openapi.util.diff.requests.DiffRequest;
import com.intellij.openapi.util.diff.requests.ErrorDiffRequest;
import com.intellij.openapi.util.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.util.diff.tools.util.DiffUserDataKeys;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeRequestChain;
import com.intellij.openapi.vcs.changes.actions.ChangeDiffRequestPresentable;
import com.intellij.openapi.vcs.changes.actions.DiffRequestPresentableProxy;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeGoToChangePopupAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

import static com.intellij.openapi.util.text.StringUtil.notNullize;

public class MigrateToNewDiffUtil {
  private static final Logger LOG = Logger.getInstance(MigrateToNewDiffUtil.class);

  @NonNls public static final Object DO_NOT_TRY_MIGRATE = "doNotTryMigrate";

  @NotNull
  public static DiffRequestChain convertRequestChain(@NotNull com.intellij.openapi.diff.DiffRequest oldRequest) {
    ChangeRequestChain oldChain = (ChangeRequestChain)oldRequest.getGenericData().get(VcsDataKeys.DIFF_REQUEST_CHAIN.getName());
    if (oldChain == null || oldChain.getAllRequests().size() < 2) {
      DiffRequest request = convertRequest(oldRequest);
      return new SimpleDiffRequestChain(Collections.singletonList(request));
    }
    else {
      return new ChangeRequestChainWrapper(oldChain);
    }
  }

  @NotNull
  public static DiffRequest convertRequest(@NotNull com.intellij.openapi.diff.DiffRequest oldRequest) {
    DiffRequest request = convertRequestFair(oldRequest);
    if (request != null) return request;

    ErrorDiffRequest erorRequest = new ErrorDiffRequest(new MyDiffRequestPresentable(oldRequest), "Can't convert from old-style request");
    erorRequest.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, Collections.<AnAction>singletonList(new MyShowDiffAction(oldRequest)));
    return erorRequest;
  }

  @Nullable
  private static DiffRequest convertRequestFair(@NotNull com.intellij.openapi.diff.DiffRequest oldRequest) {
    if (oldRequest.getOnOkRunnable() != null) return null;
    //if (oldRequest.getBottomComponent() != null) return null; // TODO: we need EDT to make this check. Let's ignore bottom component.
    // TODO: migrate layers

    com.intellij.openapi.diff.DiffContent[] contents = oldRequest.getContents();
    String[] titles = oldRequest.getContentTitles();
    if (contents.length != 2) return null;

    DiffContent content1 = convertContent(oldRequest.getProject(), contents[0]);
    DiffContent content2 = convertContent(oldRequest.getProject(), contents[1]);
    if (content1 == null || content2 == null) return null;

    SimpleDiffRequest newRequest = createSimpleRequest(content1, content2, oldRequest.getWindowTitle(), titles[0], titles[1]);

    newRequest.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, Collections.<AnAction>singletonList(new MyShowDiffAction(oldRequest)));

    DiffNavigationContext navigationContext = (DiffNavigationContext)oldRequest.getGenericData().get(DiffTool.SCROLL_TO_LINE.getName());
    if (navigationContext != null) {
      newRequest.putUserData(DiffUserDataKeys.NAVIGATION_CONTEXT, navigationContext);
    }

    return newRequest;
  }

  @Nullable
  private static DiffContent convertContent(@Nullable Project project, @NotNull final com.intellij.openapi.diff.DiffContent oldContent) {
    if (oldContent.isEmpty()) {
      return new EmptyContent();
    }
    if (oldContent.isBinary()) {
      VirtualFile file = oldContent.getFile();
      if (file == null) return null;
      return new BinaryFileContentImpl(project, file);
    }
    else {
      Document document = oldContent.getDocument();
      if (document == null) return null;
      return new DocumentContentImpl(document, oldContent.getContentType(), oldContent.getFile(), oldContent.getLineSeparator(), null) {
        @Nullable
        @Override
        public OpenFileDescriptor getOpenFileDescriptor(int offset) {
          return oldContent.getOpenFileDescriptor(offset);
        }

        @Override
        public void onAssigned(boolean isAssigned) {
          oldContent.onAssigned(isAssigned);
        }
      };
    }
  }

  @NotNull
  private static SimpleDiffRequest createSimpleRequest(@NotNull DiffContent content1,
                                                       @NotNull DiffContent content2,
                                                       @Nullable String windowTitle,
                                                       @Nullable String contentTitle1,
                                                       @Nullable String contentTitle2) {
    return new SimpleDiffRequest(notNullize(windowTitle), content1, content2, notNullize(contentTitle1), notNullize(contentTitle2));
  }


  private static class ChangeRequestChainWrapper extends UserDataHolderBase implements DiffRequestChain, GoToChangePopupBuilder.Chain {
    @NotNull private final ChangeRequestChain myChain;
    @NotNull private final List<MyPresentableWrapper> myRequests;

    private int myIndex;

    public ChangeRequestChainWrapper(@NotNull ChangeRequestChain chain) {
      myChain = chain;
      myRequests = ContainerUtil.map(myChain.getAllRequests(),
                                     new Function<com.intellij.openapi.vcs.changes.actions.DiffRequestPresentable, MyPresentableWrapper>() {
                                       @Override
                                       public MyPresentableWrapper fun(com.intellij.openapi.vcs.changes.actions.DiffRequestPresentable presentable) {
                                         return new MyPresentableWrapper(myChain, presentable);
                                       }
                                     });

      myIndex = chain.getAllRequests().indexOf(chain.getCurrentRequest());
    }

    @NotNull
    @Override
    public List<? extends MyPresentableWrapper> getRequests() {
      return myRequests;
    }

    @Override
    public int getIndex() {
      return myIndex;
    }

    @Override
    public void setIndex(int index) {
      assert index >= 0 && index < myRequests.size();
      myIndex = index;
    }

    @Nullable
    private static Change getChange(@NotNull com.intellij.openapi.vcs.changes.actions.DiffRequestPresentable presentable) {
      if (presentable instanceof DiffRequestPresentableProxy) {
        try {
          presentable = ((DiffRequestPresentableProxy)presentable).init();
        }
        catch (VcsException e) {
          LOG.info(e);
          return null;
        }
      }
      if (presentable instanceof ChangeDiffRequestPresentable) {
        return ((ChangeDiffRequestPresentable)presentable).getChange();
      }
      return null;
    }

    @NotNull
    @Override
    public AnAction createGoToChangeAction(@NotNull Consumer<Integer> onSelected) {
      return new ChangeGoToChangePopupAction<ChangeRequestChainWrapper>(this, onSelected) {
        @NotNull
        @Override
        protected List<Change> getChanges() {
          return ContainerUtil.mapNotNull(myChain.getRequests(), new Function<MyPresentableWrapper, Change>() {
            @Override
            @Nullable
            public Change fun(MyPresentableWrapper wrapper) {
              return getChange(wrapper.getPresentable());
            }
          });
        }

        @Nullable
        @Override
        protected Change getCurrentSelection() {
          return getChange(myChain.getRequests().get(myIndex).getPresentable());
        }

        @Override
        protected int findSelectedStep(@Nullable Change change) {
          if (change == null) return -1;
          for (int i = 0; i < myRequests.size(); i++) {
            Change c = getChange(myRequests.get(i).getPresentable());
            if (c != null && change.equals(c)) {
              return i;
            }
          }
          return -1;
        }
      };
    }
  }

  private static class MyPresentableWrapper implements DiffRequestPresentable {
    @NotNull private final com.intellij.openapi.vcs.changes.actions.DiffRequestPresentable myPresentable;
    @NotNull private final ChangeRequestChain myChain;

    public MyPresentableWrapper(@NotNull ChangeRequestChain chain,
                                @NotNull com.intellij.openapi.vcs.changes.actions.DiffRequestPresentable presentable) {
      myPresentable = presentable;
      myChain = chain;
    }

    @NotNull
    @Override
    public String getName() {
      return myPresentable.getPathPresentation();
    }

    @NotNull
    public com.intellij.openapi.vcs.changes.actions.DiffRequestPresentable getPresentable() {
      return myPresentable;
    }

    @NotNull
    @Override
    public DiffRequest process(@NotNull UserDataHolder context, @NotNull ProgressIndicator indicator)
      throws DiffRequestPresentableException, ProcessCanceledException {
      com.intellij.openapi.diff.DiffRequest oldRequest =
        UIUtil.invokeAndWaitIfNeeded(new Computable<com.intellij.openapi.diff.DiffRequest>() {
          @Override
          public com.intellij.openapi.diff.DiffRequest compute() {
            return myChain.moveTo(myPresentable);
          }
        });
      if (oldRequest == null) return new ErrorDiffRequest(this, "Can't build old-style request");
      return convertRequest(oldRequest);
    }
  }

  private static class MyShowDiffAction extends DumbAwareAction {
    @NotNull private final com.intellij.openapi.diff.DiffRequest myRequest;

    public MyShowDiffAction(@NotNull com.intellij.openapi.diff.DiffRequest request) {
      super("Show in old diff tool", null, AllIcons.Diff.Diff);
      setEnabledInModalContext(true);
      myRequest = request;
      request.addHint(DO_NOT_TRY_MIGRATE);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      DiffManager.getInstance().getDiffTool().show(myRequest);
    }
  }

  private static class MyDiffRequestPresentable implements DiffRequestPresentable {
    @NotNull private final com.intellij.openapi.diff.DiffRequest myRequest;

    public MyDiffRequestPresentable(@NotNull com.intellij.openapi.diff.DiffRequest request) {
      myRequest = request;
    }

    @NotNull
    @Override
    public String getName() {
      return notNullize(myRequest.getWindowTitle());
    }

    @NotNull
    @Override
    public DiffRequest process(@NotNull UserDataHolder context, @NotNull ProgressIndicator indicator)
      throws DiffRequestPresentableException, ProcessCanceledException {
      ErrorDiffRequest errorRequest = new ErrorDiffRequest(this, "Can't convert from old-style request");
      errorRequest.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, Collections.<AnAction>singletonList(new MyShowDiffAction(myRequest)));
      return errorRequest;
    }
  }
}
