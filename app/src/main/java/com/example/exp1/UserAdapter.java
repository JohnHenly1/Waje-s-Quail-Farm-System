package com.example.exp1;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.List;

public class UserAdapter extends RecyclerView.Adapter<UserAdapter.UserViewHolder> {

    private List<DocumentSnapshot> userDocs;
    private OnUserDeleteListener deleteListener;

    public interface OnUserDeleteListener {
        void onDelete(String email);
    }

    public UserAdapter(List<DocumentSnapshot> userDocs, OnUserDeleteListener deleteListener) {
        this.userDocs = userDocs;
        this.deleteListener = deleteListener;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_manage, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        DocumentSnapshot doc = userDocs.get(position);
        String name = doc.getString("name");
        String role = doc.getString("role");
        String email = doc.getId();

        holder.userNameText.setText(name != null ? name : "Unknown");
        holder.userRoleText.setText(role != null ? role : "Unknown");

        if ("owner".equals(role)) {
            holder.deleteBtn.setVisibility(View.GONE);
        } else {
            holder.deleteBtn.setVisibility(View.VISIBLE);
            holder.deleteBtn.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onDelete(email);
                }
            });
        }

        // Set initial
        if (name != null && !name.isEmpty()) {
            holder.userInitialText.setText(String.valueOf(name.charAt(0)).toUpperCase());
        }
    }

    @Override
    public int getItemCount() {
        return userDocs.size();
    }

    public void updateUsers(List<DocumentSnapshot> newDocs) {
        this.userDocs = newDocs;
        notifyDataSetChanged();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        TextView userNameText, userRoleText, userInitialText;
        ImageButton deleteBtn;

        public UserViewHolder(@NonNull View itemView) {
            super(itemView);
            userNameText = itemView.findViewById(R.id.userNameText);
            userRoleText = itemView.findViewById(R.id.userRoleText);
            userInitialText = itemView.findViewById(R.id.userInitialText);
            deleteBtn = itemView.findViewById(R.id.deleteUserBtn);
        }
    }
}
