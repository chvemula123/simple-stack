package com.example.stackmasterdetail.pathview;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

import com.example.stackmasterdetail.Paths;
import com.zhuinden.simplestack.StateChanger;

import static com.example.stackmasterdetail.Paths.MasterDetailPath;

public class DetailPathContainerView
        extends FramePathContainerView {
    public DetailPathContainerView(Context context) {
        super(context);
    }

    public DetailPathContainerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public DetailPathContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DetailPathContainerView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public StateChanger createContainer() {
        return new DetailPathContainer(this);
    }

    static class DetailPathContainer
            extends SimpleStateChanger {
        public DetailPathContainer(ViewGroup root) {
            super(root);
        }

        @Override
        protected Paths.Path getActiveKey(Paths.Path path) {
            MasterDetailPath mdPath = (MasterDetailPath) path;
            return mdPath.isMaster() ? Paths.NoDetails.create() : mdPath;
        }
    }
}
